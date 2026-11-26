#!/usr/bin/env python3
"""LoRA / QLoRA fine-tuning of an open vision-language model on JARVIS episodes (needs a GPU: a free Colab T4 can train a 2B model).

NOT RUN in this repository's CI (no GPU there). The data preparation and evaluation around it are tested; this script is a
reference implementation of the standard Hugging Face recipe, so expect to adjust it to the library versions you install:

  pip install "transformers>=4.45" peft bitsandbytes accelerate datasets pillow
  python training/train_lora.py --model HuggingFaceTB/SmolVLM2-2.2B-Instruct --data data/train.json --out out/jarvis-vlm-lora

Check the model's licence on its Hugging Face card first (code licences and weight licences are different things)."""
import argparse
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import common  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="HuggingFaceTB/SmolVLM2-2.2B-Instruct")
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", default="out/jarvis-vlm-lora")
    ap.add_argument("--epochs", type=float, default=2)
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--accum", type=int, default=8)
    ap.add_argument("--max-pixels-side", type=int, default=768)
    a = ap.parse_args()

    import torch
    from PIL import Image
    from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training
    from transformers import (AutoModelForImageTextToText, AutoProcessor, BitsAndBytesConfig, Trainer, TrainingArguments)

    processor = AutoProcessor.from_pretrained(a.model)
    bnb = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4", bnb_4bit_compute_dtype=torch.bfloat16)
    model = AutoModelForImageTextToText.from_pretrained(a.model, quantization_config=bnb, torch_dtype=torch.bfloat16, device_map="auto")
    model = prepare_model_for_kbit_training(model)
    model = get_peft_model(model, LoraConfig(r=16, lora_alpha=32, lora_dropout=0.05, target_modules="all-linear", task_type="CAUSAL_LM"))

    samples = json.loads(pathlib.Path(a.data).read_text(encoding="utf-8"))

    class DS(torch.utils.data.Dataset):
        def __len__(self):
            return len(samples)

        def __getitem__(self, i):
            return samples[i]

    def collate(batch):
        texts, images = [], []
        for s in batch:
            chat = common.to_chat(s)
            texts.append(processor.apply_chat_template(chat, add_generation_prompt=False).strip())
            imgs = []
            for p in s.get("images", []):
                im = Image.open(p).convert("RGB")
                im.thumbnail((a.max_pixels_side, a.max_pixels_side))
                imgs.append(im)
            images.append(imgs)
        enc = processor(text=texts, images=images if any(images) else None, return_tensors="pt", padding=True)
        labels = enc["input_ids"].clone()
        labels[labels == processor.tokenizer.pad_token_id] = -100
        enc["labels"] = labels
        return enc

    args = TrainingArguments(output_dir=a.out, num_train_epochs=a.epochs, per_device_train_batch_size=1, gradient_accumulation_steps=a.accum,
                             learning_rate=a.lr, bf16=True, logging_steps=10, save_strategy="epoch", remove_unused_columns=False, report_to="none")
    Trainer(model=model, args=args, train_dataset=DS(), data_collator=collate).train()
    model.save_pretrained(a.out)
    processor.save_pretrained(a.out)
    print("saved LoRA adapter to", a.out)


if __name__ == "__main__":
    main()
