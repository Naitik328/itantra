#!/bin/bash
set -e

# Delete any stale cache before training on a fresh metadata.csv -- this
# volume has hit "Disk quota exceeded" more than once from accumulated
# caches/checkpoints across languages.
rm -rf /workspace/piper_work/te_cache/

# HISTORY -- seven straight CUDA OOM crashes were chased here before finding
# the real cause. Each one blamed a symptom (worker-race cache collisions,
# batch_size, allocator fragmentation) and "fixed" it with batch_size=8,
# num_workers=1, PYTORCH_CUDA_ALLOC_CONF, and an arbitrary text-length trim
# -- none of it actually fixed anything, because none of it was the cause.
#
# The real bug: piper1-gpl reads metadata.csv with Python's
# csv.reader(delimiter="|"), whose default dialect treats `"` as a quote
# character. 229 of our rows contained a literal `"`. An unbalanced quote
# makes csv.reader swallow every following "|"-delimited line into ONE field
# until a closing quote turns up -- silently merging dozens of unrelated
# rows into a single row with a multi-thousand-character "text". Reparsing
# our own metadata.csv with the exact same csv.reader call it was clear:
# 12,612 real rows collapsed into 10,050 parsed rows, and the worst merged
# row was 17,870 characters (row "2216", which is exactly the row number
# that showed up mangled into corrupted cache filenames on the very first
# crash -- that was the bug showing itself from the start, not a random
# race). A text that long phonemizes into thousands of tokens; self-attention
# memory is O(batch x T^2), so THAT is where 26.95 GiB came from, not
# anything about Telugu itself or the dataset's genuine content (the real
# longest utterance, verified via piper's own EspeakPhonemizer, is 259
# phonemes -- trivial memory-wise).
#
# Fix: 1_download_dataset.py now strips literal `"` from text before writing
# metadata.csv. Re-parsing with csv.reader after the fix: 12,612 rows in,
# 12,612 rows out, longest text 193 characters. No data was actually bad or
# needed trimming -- back to the full female-filtered, duration-filtered set
# and Hindi/English's original fast config (batch_size=32, num_workers=8).
cd /workspace/piper_work/piper1-gpl
python3 -m piper.train fit \
  --data.voice_name "te_finetune" \
  --data.csv_path /workspace/te_data/metadata.csv \
  --data.audio_dir /workspace/te_data/audio \
  --model.sample_rate 22050 \
  --data.espeak_voice "te" \
  --data.cache_dir /workspace/piper_work/te_cache/ \
  --data.config_path /workspace/piper_work/te_data_config.json \
  --data.batch_size 32 \
  --data.num_workers 8 \
  --data.num_test_examples 2 \
  --data.validation_split 0.01 \
  --model.warmstart_ckpt /workspace/piper_work/base_checkpoint.ckpt \
  --trainer.max_epochs 30 \
  --trainer.devices 1 \
  --trainer.accelerator gpu \
  --trainer.precision 16-mixed \
  --trainer.default_root_dir /workspace/piper_work/lightning_logs_te 2>&1 | tee /workspace/piper_work/train_te.log
