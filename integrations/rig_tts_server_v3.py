#!/usr/bin/env python3
"""Rig-side TTS server — Qwen3-TTS on CUDA device 1 (the 3060).

Endpoints:
  POST /tts          {"text": "...", "speaker": "ryan"} -> WAV bytes (24k mono)
                     Uses Qwen3-TTS-12Hz-1.7B-CustomVoice (9 built-in timbres).
  POST /tts          {"text": "...", "voice": "my_voice"} -> WAV bytes
                     Uses the stored clone profile for a created voice
                     (Qwen3-TTS-12Hz-1.7B-Base + profile).
  POST /voice_create {"name": "my_voice", "instruct": "a warm...", "ref_text": "..."}
                     -> {"ok": true}
                     Designs a reference clip with VoiceDesign, builds a reusable
                     clone profile with Base, persists it to disk.
  GET  /voices       -> {"voices": [{"name": ..., "instruct": ..., "ref_text": ...}]}
  POST /voice_delete {"name": "my_voice"} -> {"ok": true}
  GET  /health       -> {"ok": true}

Only ONE 1.7B model is resident at a time (VRAM is shared with the 27B Qwen LLM
tensor-split across both GPUs). Models lazy-load and swap on demand.
Custom voices are persisted as clone profiles under VOICES_DIR.
"""
import json, io, os, threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import torch, soundfile as sf
from qwen_tts import Qwen3TTSModel

CUSTOM_PATH = os.environ.get("WB_TTS_MODEL", r"C:\Users\jenni\wb-local\models\CustomVoice")
DESIGN_PATH = os.environ.get("WB_VOICEDESIGN_MODEL", r"C:\Users\jenni\wb-local\models\VoiceDesign")
BASE_PATH = os.environ.get("WB_BASE_MODEL", r"C:\Users\jenni\wb-local\models\Base")
VOICES_DIR = os.environ.get("WB_VOICES_DIR", r"C:\Users\jenni\wb-local\voices")
DEVICE = os.environ.get("WB_TTS_DEVICE", "cuda:1")
SPEAKERS = {"ryan", "eric", "serena", "vivian", "dylan", "aiden", "uncle_fu", "ono_anna", "sohee"}
DEFAULT_REF_TEXT = "Hello! This is my voice. It has a personality all its own."

_lock = threading.Lock()
_custom_model = None
_design_model = None
_base_model = None
_voices = {}  # name -> {"instruct": str, "ref_text": str, "profile": list[VoiceClonePromptItem]}


# ---------------------------------------------------------------- model mgmt
def _load_model(path):
    print(f"[tts] loading {os.path.basename(path)} on {DEVICE}", flush=True)
    model = Qwen3TTSModel.from_pretrained(
        path, device_map=DEVICE, dtype=torch.bfloat16, attn_implementation="sdpa",
    )
    print(f"[tts] {os.path.basename(path)} ready", flush=True)
    return model


def get_custom_model():
    global _custom_model, _design_model, _base_model
    with _lock:
        if _custom_model is None:
            _design_model = None
            _base_model = None
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
            _custom_model = _load_model(CUSTOM_PATH)
        return _custom_model


def get_design_model():
    global _custom_model, _design_model, _base_model
    with _lock:
        if _design_model is None:
            _custom_model = None
            _base_model = None
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
            _design_model = _load_model(DESIGN_PATH)
        return _design_model


def get_base_model():
    global _custom_model, _design_model, _base_model
    with _lock:
        if _base_model is None:
            _custom_model = None
            _design_model = None
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
            _base_model = _load_model(BASE_PATH)
        return _base_model


# ---------------------------------------------------------------- voice store
def _safe_name(name):
    keep = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_ .")
    cleaned = "".join(c if c in keep else "_" for c in name).strip()
    return cleaned or "voice"


def _voice_dir(name):
    return os.path.join(VOICES_DIR, _safe_name(name))


def load_voices():
    global _voices
    _voices = {}
    if not os.path.isdir(VOICES_DIR):
        return
    for d in os.listdir(VOICES_DIR):
        meta_p = os.path.join(VOICES_DIR, d, "meta.json")
        prof_p = os.path.join(VOICES_DIR, d, "profile.pt")
        if os.path.isfile(meta_p) and os.path.isfile(prof_p):
            try:
                with open(meta_p, "r", encoding="utf-8") as f:
                    meta = json.load(f)
                profile = torch.load(prof_p, map_location="cpu", weights_only=False)
                _voices[meta["name"]] = {"instruct": meta.get("instruct", ""), "ref_text": meta.get("ref_text", ""), "profile": profile}
            except Exception as e:
                print(f"[tts] failed to load voice {d}: {e}", flush=True)


def persist_voice(name, instruct, ref_text, profile):
    os.makedirs(_voice_dir(name), exist_ok=True)
    meta = {"name": name, "instruct": instruct, "ref_text": ref_text}
    with open(os.path.join(_voice_dir(name), "meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False)
    torch.save(profile, os.path.join(_voice_dir(name), "profile.pt"))
    _voices[name] = {"instruct": instruct, "ref_text": ref_text, "profile": profile}


def delete_voice(name):
    import shutil
    d = _voice_dir(name)
    if os.path.isdir(d):
        shutil.rmtree(d)
    _voices.pop(name, None)


# ---------------------------------------------------------------- handlers
class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path == "/health":
            self._json({"ok": True})
        elif self.path == "/voices":
            self._json({"voices": [{"name": k, "instruct": v["instruct"], "ref_text": v["ref_text"]} for k, v in _voices.items()]})
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
        elif self.path == "/voice_create":
            self._handle_voice_create(body)
        elif self.path == "/voice_delete":
            self._handle_voice_delete(body)
        else:
            self.send_response(404)
            self.end_headers()

    def _handle_tts(self, body):
        try:
            text = (body.get("text") or "").strip()
            if not text:
                raise ValueError("empty text")
            voice = (body.get("voice") or "").strip()
            if voice:
                if voice not in _voices:
                    raise ValueError(f"unknown voice: {voice}")
                model = get_base_model()
                wavs, sr = model.generate_voice_clone(
                    text=text, language="English", voice_clone_prompt=_voices[voice]["profile"],
                )
                self._wav(wavs[0], sr)
                return
            speaker = body.get("speaker", "ryan")
            if speaker not in SPEAKERS:
                speaker = "ryan"
            model = get_custom_model()
            wavs, sr = model.generate_custom_voice(text=text, language="English", speaker=speaker)
            self._wav(wavs[0], sr)
        except Exception as e:
            self._json({"error": str(e)}, code=500)

    def _handle_voice_create(self, body):
        try:
            name = _safe_name(body.get("name") or "")
            if not name:
                raise ValueError("empty name")
            if name in _voices:
                raise ValueError(f"voice already exists: {name}")
            instruct = (body.get("instruct") or "").strip()
            if not instruct:
                raise ValueError("empty instruct")
            ref_text = (body.get("ref_text") or "").strip() or DEFAULT_REF_TEXT
            # 1) Design a reference clip with VoiceDesign
            design = get_design_model()
            ref_wavs, sr = design.generate_voice_design(text=ref_text, instruct=instruct, language="English")
            # 2) Build a reusable clone profile with Base
            base = get_base_model()
            profile = base.create_voice_clone_prompt(ref_audio=(ref_wavs[0], sr), ref_text=ref_text)
            persist_voice(name, instruct, ref_text, profile)
            self._json({"ok": True, "name": name})
        except Exception as e:
            self._json({"error": str(e)}, code=500)

    def _handle_voice_delete(self, body):
        try:
            name = (body.get("name") or "").strip()
            if not name:
                raise ValueError("empty name")
            delete_voice(name)
            self._json({"ok": True})
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
    load_voices()
    port = int(os.environ.get("WB_TTS_PORT", "8799"))
    HTTPServer(("0.0.0.0", port), H).serve_forever()
