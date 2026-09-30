"""Captura o áudio que sai nas caixas do Windows (WASAPI loopback) em PCM 16 bits.

Uma única thread serve todos os celulares; só captura enquanto alguém pediu áudio.
"""
import threading
import time


class AudioCapture:
    def __init__(self, on_chunk, log=print):
        self.on_chunk = on_chunk  # (rate, channels, pcm_bytes) — chamado na thread de captura
        self.log = log
        self.available = None  # None = ainda não tentou; False = indisponível
        self._fmt = (32000, 2)
        self._demand = False
        self._wake = threading.Event()
        self._thread = None
        self._lock = threading.Lock()

    def set(self, demand: bool, rate=None, channels=None):
        with self._lock:
            if rate and channels:
                self._fmt = (int(rate), int(channels))
            self._demand = bool(demand)
            if demand:
                if self._thread is None:
                    self._thread = threading.Thread(target=self._run, daemon=True)
                    self._thread.start()
                self._wake.set()

    def _run(self):
        try:
            import numpy as np
            import soundcard as sc
        except Exception as e:
            self.available = False
            self.log(f"Áudio indisponível: {e}")
            return
        self.available = True
        while True:
            self._wake.wait()
            if not self._demand:
                self._wake.clear()
                continue
            rate, ch = self._fmt
            try:
                spk = sc.default_speaker()
                mic = sc.get_microphone(id=str(spk.name), include_loopback=True)
                frames = max(rate // 33, 160)  # ~30 ms por pacote
                with mic.recorder(samplerate=rate, channels=ch, blocksize=frames * 2) as rec:
                    last_check = time.time()
                    while self._demand and self._fmt == (rate, ch):
                        data = rec.record(numframes=frames)
                        pcm = (np.clip(data, -1.0, 1.0) * 32767).astype("<i2").tobytes()
                        if any(pcm):  # não gasta banda com silêncio
                            self.on_chunk(rate, ch, pcm)
                        # trocou o dispositivo de saída padrão? reabre no novo
                        if time.time() - last_check > 3:
                            last_check = time.time()
                            if str(sc.default_speaker().name) != str(spk.name):
                                break
            except Exception as e:
                self.log(f"Áudio: {e}")
                time.sleep(2)
