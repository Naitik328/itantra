#!/bin/bash
set -e
cd /workspace/piper_work/piper1-gpl
python3 -m piper.train fit \
  --data.voice_name "en_finetune" \
  --data.csv_path /workspace/en_data/metadata.csv \
  --data.audio_dir /workspace/en_data/audio \
  --model.sample_rate 22050 \
  --data.espeak_voice "en-us" \
  --data.cache_dir /workspace/piper_work/en_cache/ \
  --data.config_path /workspace/piper_work/en_data_config.json \
  --data.batch_size 32 \
  --data.num_workers 8 \
  --data.num_test_examples 2 \
  --data.validation_split 0.01 \
  --model.warmstart_ckpt /workspace/piper_work/base_checkpoint.ckpt \
  --trainer.max_epochs 30 \
  --trainer.devices 1 \
  --trainer.accelerator gpu \
  --trainer.precision 16-mixed \
  --trainer.default_root_dir /workspace/piper_work/lightning_logs_en 2>&1 | tee /workspace/piper_work/train_en.log
