#!/usr/bin/env python3
"""Rig-side TTS server — Qwen3-TTS on CUDA device 1 (the 3060).

Endpoints:
  POST /tts          {"text": "...", "speaker": "ryan"} -> WAV bytes (24k mono)
                     Uses Qwen3-TTS-12Hz-1.7B-CustomVoice (9 built-in timbres).
  POST /voice_design {"text": "...", "instruct": "...", "language": "English"}
                     -> WAV bytes (24k mono)
                     Uses Qwen3-TTS-12Hz-1.7B-VoiceDesign (create a voice from a
                     natural-language prompt). Language: English/Chinese/Japanese/
                     Korean/German/French/Russian/Portuguese/Spanish/Italian.
  GET  /health       -> {"ok": true}

Only ONE 1.7B model is resident at a time (VRAM is shared with the 27B Qwen LLM
tensor-split across both GPUs). Calling /voice_design loads VoiceDesign and
unloads CustomVoice; calling /tts does the reverse. First call after a swap is
slow (model load); subsequent calls are fast.
"""
import json, io, os, threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import torch, soundfile as sf
from qwen_tts import Qwen3TTSModel

CUSTOM_PATH = os.environ.get("WB_TTS_MODEL", r"C:\Users\jenni\wb-local\models\CustomVoice")
DESIGN_PATH = os.environ.get("WB_VOICEDESIGN_MODEL", r"C:\Users\jenni\wb-local\models\VoiceDesign")
DEVICE = os.environ.get("WB_TTS_DEVICE", "cuda:1")
SPEAKERS = {"ryan", "eric", "serena", "vivian", "dylan", "aiden", "uncle_fu", "ono_anna", "sohee"}

_lock = threading.Lock()
_custom_model = None
_design_model = None


def _load_model(path):
    print(f"[tts] loading {os.path.basename(path)} on {DEVICE}", flush=True)
    model = Qwen3TTSModel.from_pretrained(
        path, device_map=DEVICE, dtype=torch.bfloat16, attn_implementation="sdpa",
    )
    print(f"[tts] {os.path.basename(path)} ready", flush=True)
    return model


def get_custom_model():
    """Load (or re-load) the CustomVoice model, freeing VoiceDesign if resident."""
    global _custom_model, _design_model
    with _lock:
        if _custom_model is None:
            _design_model = None
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
            _custom_model = _load_model(CUSTOM_PATH)
        return _custom_model


def get_design_model():
    """Load (or re-load) the VoiceDesign model, freeing CustomVoice if resident."""
    global _custom_model, _design_model
    with _lock:
        if _design_model is None:
            _custom_model = None
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
            _design_model = _load_model(DESIGN_PATH)
        return _design_model


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path == "/health":
            self._json({"ok": True})
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        try:
            n = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            self._json({"error": "bad request"}, code=400)
            return
        if self.path == "/tts":
            self._handle_tts(body)
        elif self.path == "/voice_design":
            self._handle_voice_design(body)
        else:
            self.send_response(404)
            self.end_headers()

    def _handle_tts(self, body):
        try:
            text = (body.get("text") or "").strip()
            if not text:
                raise ValueError("empty text")
            speaker = body.get("speaker", "ryan")
            if speaker not in SPEAKERS:
                speaker = "ryan"
            model = get_custom_model()
            wavs, sr = model.generate_custom_voice(text=text, language="English", speaker=speaker)
            self._wav(wavs[0], sr)
        except Exception as e:
            self._json({"error": str(e)}, code=500)

    def _handle_voice_design(self, body):
        try:
            text = (body.get("text") or "").strip()
            if not text:
                raise ValueError("empty text")
            instruct = (body.get("instruct") or "").strip()
            if not instruct:
                raise ValueError("empty instruct — describe the voice you want")
            language = (body.get("language") or "English").strip() or "English"
            model = get_design_model()
            wavs, sr = model.generate_voice_design(
                text=text, instruct=instruct, language=language,
            )
            self._wav(wavs[0], sr)
        except Exception as e:
            self._json({"error": str(e)}, code=500)

    def _wav(self, wav, sr):
        buf = io.BytesIO()
        sf.write(buf, wav, sr, format="WAV", subtype="PCM_16")
        data = buf.getvalue()
        self.send_response(200)
        self.send_header("Content-Type", "audio/wav")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _json(self, obj, code=200):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    port = int(os.environ.get("WB_TTS_PORT", "8799"))
    HTTPServer(("0.0.0.0", port), H).serve_forever()
