# Native-only codec lab. Deliberately NOT a PlatformIO env: nothing here is
# ever compiled into the ESP32 firmware.

CXX      ?= clang++
CC       ?= clang
CXXFLAGS := -std=c++17 -O2 -Wall -Wextra -Isrc -Ivendor/unishox2 \
            -I../lib/protocol -I../test
CFLAGS   := -O2 -Ivendor/unishox2 -Wno-unused-parameter

SRCS := src/main.cpp src/utf8.cpp src/huffman_codec.cpp src/huffman_train.cpp \
        ../lib/protocol/airtime.cpp ../lib/protocol/fragmenter.cpp \
        ../lib/protocol/packet.cpp ../lib/protocol/crc16.cpp
OBJS := $(patsubst %,build/%.o,$(notdir $(SRCS)))

# A fresh clone has no build/ directory; create it at parse time so the very
# first `make` works rather than failing on a missing output path.
$(shell mkdir -p build)

all: build/codeclab

build/codeclab: $(OBJS) build/unishox2.o
	$(CXX) $(CXXFLAGS) -o $@ $^

build/unishox2.o: vendor/unishox2/unishox2.c
	$(CC) $(CFLAGS) -c -o $@ $<

build/%.cpp.o: src/%.cpp
	$(CXX) $(CXXFLAGS) -c -o $@ $<

build/%.cpp.o: ../lib/protocol/%.cpp
	$(CXX) $(CXXFLAGS) -c -o $@ $<

analyze: build/codeclab
	@cd . && ./build/codeclab analyze
train: build/codeclab
	@./build/codeclab train
bench: build/codeclab
	@./build/codeclab bench
test: build/codeclab
	@./build/codeclab test

clean:
	rm -f build/*.o build/codeclab

.PHONY: all analyze train bench test clean
