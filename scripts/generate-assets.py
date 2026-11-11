"""Genera icone PWA e suoni di avviso per Claude Monitor."""
import math
import struct
import sys
import wave

from PIL import Image, ImageDraw

out = sys.argv[1]

# --- Icone: sfondo scuro pieno (adatto anche a maskable) con tre "card" e un punto di attenzione
def icon(size):
    scale = 4
    s = size * scale
    img = Image.new("RGB", (s, s), "#0b0f17")
    d = ImageDraw.Draw(img)
    u = s / 100
    bars = [("#ef4444", 30), ("#f97316", 46), ("#22c55e", 62)]
    for color, y in bars:
        d.rounded_rectangle([24 * u, y * u, 76 * u, (y + 11) * u], radius=3 * u, fill="#1c2434")
        d.rounded_rectangle([24 * u, y * u, 28 * u, (y + 11) * u], radius=2 * u, fill=color)
        d.ellipse([33 * u, (y + 3.5) * u, 37 * u, (y + 7.5) * u], fill=color)
        d.rounded_rectangle([42 * u, (y + 4) * u, 68 * u, (y + 7) * u], radius=1.5 * u, fill="#3a455c")
    return img.resize((size, size), Image.LANCZOS)

for size in (72, 96, 128, 144, 152, 192, 384, 512):
    icon(size).save(f"{out}/icons/icon-{size}x{size}.png")
icon(180).save(f"{out}/icons/apple-touch-icon.png")
icon(48).save(f"{out}/favicon.ico", sizes=[(16, 16), (32, 32), (48, 48)])

# --- Suoni: WAV mono 16 bit, sotto il secondo
RATE = 44100

def write(name, samples):
    with wave.open(f"{out}/sounds/{name}", "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, v)) * 32767)) for v in samples))

def tone(freq, duration, attack=0.008, decay=6.0, harmonics=0.0):
    n = int(RATE * duration)
    result = []
    for i in range(n):
        t = i / RATE
        env = min(1.0, t / attack) * math.exp(-decay * t)
        # Dissolvenza finale per evitare click
        env *= min(1.0, (duration - t) / 0.01)
        v = math.sin(2 * math.pi * freq * t) + harmonics * math.sin(2 * math.pi * freq * 2 * t)
        result.append(0.6 * env * v / (1 + harmonics))
    return result

def silence(duration):
    return [0.0] * int(RATE * duration)

# Insistente: tre bip acuti ravvicinati (circa 0,65 s)
beep = tone(1175, 0.14, decay=3.0, harmonics=0.35)
write("needs-attention.wav", beep + silence(0.07) + beep + silence(0.07) + tone(1568, 0.22, decay=5.0, harmonics=0.35))

# Discreto: due note morbide ascendenti (circa 0,55 s)
write("waiting-input.wav", tone(660, 0.2, attack=0.02, decay=9.0) + tone(880, 0.35, attack=0.02, decay=8.0))
