# Builds translate_cli against a local ONNX Runtime C++ SDK (headers + lib)
# and nlohmann/json. Neither is checked into this repo (large, easy to
# fetch) -- see README.md for exact download commands, or just point
# these variables at wherever you already have them.
#
# Usage:
#   make ORT_ROOT=/path/to/onnxruntime-linux-x64-1.29.0 JSON_INCLUDE=/path/to/include-extra
#   ./translate_cli --model-root ... --extensions-lib ...

ORT_ROOT ?= $(HOME)/.local/ort-cpp/onnxruntime-linux-x64-1.29.0
JSON_INCLUDE ?= $(HOME)/.local/ort-cpp/include-extra

CXX ?= g++
CXXFLAGS = -std=c++17 -O2 -Wall -Wextra \
           -I$(ORT_ROOT)/include -I$(JSON_INCLUDE)
LDFLAGS = -L$(ORT_ROOT)/lib -lonnxruntime -Wl,-rpath,$(ORT_ROOT)/lib

translate_cli: translate_cli.cpp
	$(CXX) $(CXXFLAGS) -o $@ $< $(LDFLAGS)

clean:
	rm -f translate_cli

.PHONY: clean
