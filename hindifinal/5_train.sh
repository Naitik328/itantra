#!/bin/bash
# Record of the exact command used for the Hindi fine-tune (run interactively at the time, not saved as a script then).
set -e
cd /workspace/piper_work/piper1-gpl
python3 -m piper.train fit \
  --data.voice_name "hi_finetune" \
  --data.csv_path /workspace/hi_data/metadata.csv \
  --data.audio_dir /workspace/hi_data/audio \
  --model.sample_rate 22050 \
  --data.espeak_voice "hi" \
  --data.cache_dir /workspace/piper_work/hi_cache/ \
  --data.config_path /workspace/piper_work/hi_data_config.json \
  --data.batch_size 8 \
  --data.num_test_examples 2 \
  --data.validation_split 0.01 \
  --model.warmstart_ckpt /workspace/piper_work/base_checkpoint.ckpt \
  --trainer.max_epochs 30 \
  --trainer.devices 1 \
  --trainer.accelerator gpu \
  --trainer.precision 16-mixed \
  --trainer.default_root_dir /workspace/piper_work/lightning_logs 2>&1 | tee /workspace/piper_work/train.log
