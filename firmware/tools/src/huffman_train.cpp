#include "huffman_train.h"

#include <algorithm>
#include <cstdio>
#include <numeric>
#include <queue>
#include <stdexcept>

namespace lorax::codec::train {

namespace {

struct Node {
    uint64_t freq;
    int      left  = -1;
    int      right = -1;
    uint32_t sym   = 0;
    bool     leaf  = false;
};

// Min-heap ordered by frequency, then by insertion index so the result is
// deterministic across runs and platforms. Non-determinism here would mean the
// generated tables churn in git for no reason.
struct HeapItem {
    uint64_t freq;
    int      order;
    int      node;
};
struct HeapCmp {
    bool operator()(const HeapItem& a, const HeapItem& b) const {
        if (a.freq != b.freq) return a.freq > b.freq;
        return a.order > b.order;
    }
};

void assignDepths(const std::vector<Node>& nodes, int root,
                  std::map<uint32_t, uint8_t>& depths) {
    struct Frame { int node; uint8_t depth; };
    std::vector<Frame> stack{{root, 0}};
    while (!stack.empty()) {
        const Frame f = stack.back();
        stack.pop_back();
        const Node& n = nodes[f.node];
        if (n.leaf) {
            // A single-symbol alphabet would give depth 0, which is not a
            // usable code. Floor at 1 bit.
            depths[n.sym] = f.depth == 0 ? 1 : f.depth;
            continue;
        }
        stack.push_back({n.left, static_cast<uint8_t>(f.depth + 1)});
        stack.push_back({n.right, static_cast<uint8_t>(f.depth + 1)});
    }
}

// Clamp to `limit`, then repair the Kraft sum so the code is still complete.
// Never triggers for alphabets of a few dozen symbols with balanced-ish
// frequencies, but a pathological corpus must not silently produce a code the
// uint16_t code field cannot hold.
bool limitLengths(std::vector<uint8_t>& lens, uint8_t limit) {
    bool applied = false;
    for (auto& l : lens) {
        if (l > limit) {
            l = limit;
            applied = true;
        }
    }
    if (!applied) return false;

    const uint64_t one = 1ULL << limit;
    auto kraft = [&]() {
        uint64_t total = 0;
        for (uint8_t l : lens) total += one >> l;
        return total;
    };

    // Over-committed: lengthen the shallowest codes until it fits.
    while (kraft() > one) {
        size_t victim = 0;
        uint8_t best = 0;
        bool found = false;
        for (size_t i = 0; i < lens.size(); ++i) {
            if (lens[i] < limit && (!found || lens[i] > best)) {
                best = lens[i];
                victim = i;
                found = true;
            }
        }
        if (!found) break;
        ++lens[victim];
    }
    // Under-committed: shorten where we can, otherwise we waste bits.
    for (;;) {
        const uint64_t total = kraft();
        if (total >= one) break;
        size_t victim = 0;
        uint8_t best = 0;
        bool found = false;
        for (size_t i = 0; i < lens.size(); ++i) {
            if (lens[i] > 1 && (one >> (lens[i] - 1)) + total - (one >> lens[i]) <= one &&
                (!found || lens[i] > best)) {
                best = lens[i];
                victim = i;
                found = true;
            }
        }
        if (!found) break;
        --lens[victim];
    }
    return true;
}

}  // namespace

HuffTable TrainedTable::view() const {
    HuffTable t{};
    t.symbols     = symbols.data();
    t.lengths     = lengths.data();
    t.codes       = codes.data();
    t.sortedIdx   = sortedIdx.data();
    t.countPerLen = countPerLen.data();
    t.firstCode   = firstCode.data();
    t.firstIndex  = firstIndex.data();
    t.symbolCount = static_cast<uint16_t>(symbols.size());
    t.maxLength   = maxLength;
    return t;
}

size_t TrainedTable::flashBytes() const {
    return tableFlashBytes(view());
}

TrainedTable train(const std::map<uint32_t, uint64_t>& freqs) {
    std::map<uint32_t, uint64_t> all = freqs;
    // Always present: without ESCAPE unknown input is unencodable, and without
    // EOS the stream cannot terminate itself.
    all[SYM_ESCAPE] += 1;
    all[SYM_EOS] += 1;

    std::vector<Node> nodes;
    std::priority_queue<HeapItem, std::vector<HeapItem>, HeapCmp> heap;
    int order = 0;
    for (const auto& [sym, f] : all) {
        Node n;
        n.freq = f;
        n.sym  = sym;
        n.leaf = true;
        nodes.push_back(n);
        heap.push({f, order++, static_cast<int>(nodes.size()) - 1});
    }
    if (nodes.empty()) {
        throw std::runtime_error("empty alphabet");
    }

    while (heap.size() > 1) {
        const HeapItem a = heap.top(); heap.pop();
        const HeapItem b = heap.top(); heap.pop();
        Node parent;
        parent.freq  = a.freq + b.freq;
        parent.left  = a.node;
        parent.right = b.node;
        parent.leaf  = false;
        nodes.push_back(parent);
        heap.push({parent.freq, order++, static_cast<int>(nodes.size()) - 1});
    }

    std::map<uint32_t, uint8_t> depths;
    assignDepths(nodes, heap.top().node, depths);

    std::vector<uint32_t> syms;
    std::vector<uint8_t>  lens;
    syms.reserve(depths.size());
    lens.reserve(depths.size());
    for (const auto& [s, d] : depths) {
        syms.push_back(s);
        lens.push_back(d);
    }

    TrainedTable out;
    out.lengthLimitApplied = limitLengths(lens, MAX_CODE_BITS);

    // Canonical order: by code length, then by symbol value.
    std::vector<size_t> order2(syms.size());
    std::iota(order2.begin(), order2.end(), 0);
    std::sort(order2.begin(), order2.end(), [&](size_t a, size_t b) {
        if (lens[a] != lens[b]) return lens[a] < lens[b];
        return syms[a] < syms[b];
    });

    out.maxLength = *std::max_element(lens.begin(), lens.end());
    out.countPerLen.assign(out.maxLength + 1, 0);
    for (uint8_t l : lens) out.countPerLen[l]++;

    out.symbols.resize(syms.size());
    out.lengths.resize(syms.size());
    out.codes.resize(syms.size());
    for (size_t i = 0; i < order2.size(); ++i) {
        out.symbols[i] = syms[order2[i]];
        out.lengths[i] = lens[order2[i]];
    }

    out.firstCode.assign(out.maxLength + 1, 0);
    out.firstIndex.assign(out.maxLength + 1, 0);
    uint32_t code = 0;
    uint16_t cumulative = 0;
    for (uint8_t len = 1; len <= out.maxLength; ++len) {
        out.firstCode[len]  = static_cast<uint16_t>(code);
        out.firstIndex[len] = cumulative;
        for (uint16_t k = 0; k < out.countPerLen[len]; ++k) {
            out.codes[cumulative + k] = static_cast<uint16_t>(code + k);
        }
        code = (code + out.countPerLen[len]) << 1;
        cumulative = static_cast<uint16_t>(cumulative + out.countPerLen[len]);
    }

    out.sortedIdx.resize(out.symbols.size());
    std::iota(out.sortedIdx.begin(), out.sortedIdx.end(), 0);
    std::sort(out.sortedIdx.begin(), out.sortedIdx.end(),
              [&](uint16_t a, uint16_t b) { return out.symbols[a] < out.symbols[b]; });

    return out;
}

std::string emitCpp(const TrainedTable& t, const std::string& name) {
    auto line = [](const char* fmt, auto... args) {
        char buf[256];
        std::snprintf(buf, sizeof(buf), fmt, args...);
        return std::string(buf);
    };
    std::string s;
    s += "// " + name + ": " + std::to_string(t.symbols.size()) + " symbols, max " +
         std::to_string(t.maxLength) + " bits, " + std::to_string(t.flashBytes()) +
         " bytes of flash\n";

    auto arr = [&](const char* type, const char* suffix, auto& v, int perLine, bool hex) {
        s += line("static const %s %s_%s[] = {", type, name.c_str(), suffix);
        for (size_t i = 0; i < v.size(); ++i) {
            if (i % perLine == 0) s += "\n    ";
            s += hex ? line("0x%06X, ", static_cast<unsigned>(v[i]))
                     : line("%u, ", static_cast<unsigned>(v[i]));
        }
        s += "\n};\n";
    };
    arr("uint32_t", "symbols", t.symbols, 8, true);
    arr("uint8_t", "lengths", t.lengths, 16, false);
    arr("uint16_t", "codes", t.codes, 12, false);
    arr("uint16_t", "sortedIdx", t.sortedIdx, 16, false);
    arr("uint16_t", "countPerLen", t.countPerLen, 16, false);
    arr("uint16_t", "firstCode", t.firstCode, 12, false);
    arr("uint16_t", "firstIndex", t.firstIndex, 12, false);

    s += line("const HuffTable %s = {\n", name.c_str());
    s += line("    %s_symbols, %s_lengths, %s_codes, %s_sortedIdx,\n",
              name.c_str(), name.c_str(), name.c_str(), name.c_str());
    s += line("    %s_countPerLen, %s_firstCode, %s_firstIndex,\n",
              name.c_str(), name.c_str(), name.c_str());
    s += line("    %u, %u,\n", static_cast<unsigned>(t.symbols.size()),
              static_cast<unsigned>(t.maxLength));
    s += "};\n";
    return s;
}

}  // namespace lorax::codec::train
