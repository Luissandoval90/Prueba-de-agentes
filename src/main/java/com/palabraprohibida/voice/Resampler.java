package com.palabraprohibida.voice;

/**
 * Pasa-bajos FIR (sinc con ventana Hamming, 33 taps, corte ~7 kHz) + decimacion
 * de 48 kHz a 16 kHz, con historia entre cuadros y ganancia suave (hasta x6).
 *
 * Un ejemplar por hablante, usado solo desde el hilo de reconocimiento.
 */
public final class Resampler {
	public static final int IN_RATE = 48_000;
	public static final int OUT_RATE = 16_000;
	public static final int DECIMATE = IN_RATE / OUT_RATE; // 3
	private static final int TAPS = 33;
	private static final double CUTOFF_HZ = 7_000.0;

	private final float[] kernel = new float[TAPS];
	/** Linea de retardo circular: historia entre cuadros. */
	private final float[] delay = new float[TAPS];
	private int writePos;
	private int phase;
	private float gain = 1.0f;
	private int framesSinceGain;

	public Resampler() {
		int mid = (TAPS - 1) / 2;
		double fc = CUTOFF_HZ / IN_RATE;
		double sum = 0.0;
		for (int i = 0; i < TAPS; i++) {
			int n = i - mid;
			double sinc = n == 0 ? 2.0 * fc : Math.sin(2.0 * Math.PI * fc * n) / (Math.PI * n);
			double window = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / (TAPS - 1));
			kernel[i] = (float) (sinc * window);
			sum += kernel[i];
		}
		if (sum != 0.0) {
			for (int i = 0; i < TAPS; i++) {
				kernel[i] /= (float) sum;
			}
		}
	}

	/** Reinicia la historia y la ganancia. */
	public void reset() {
		java.util.Arrays.fill(delay, 0.0f);
		writePos = 0;
		phase = 0;
		gain = 1.0f;
		framesSinceGain = 0;
	}

	/** Convierte un cuadro PCM de 48 kHz y devuelve las muestras de 16 kHz. */
	public short[] process(short[] input) {
		if (input == null || input.length == 0) {
			return new short[0];
		}
		updateGain(input);
		short[] scratch = new short[input.length / DECIMATE + 1];
		int outIndex = 0;
		for (short sample : input) {
			delay[writePos] = sample;
			writePos = (writePos + 1) % TAPS;
			if (phase == 0) {
				float acc = 0.0f;
				for (int k = 0; k < TAPS; k++) {
					int index = (writePos + k) % TAPS; // de mas antiguo a mas nuevo
					acc += delay[index] * kernel[k];
				}
				int value = Math.round(acc * gain);
				scratch[outIndex++] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
			}
			phase = (phase + 1) % DECIMATE;
		}
		if (outIndex == 0) {
			return new short[0];
		}
		short[] output = new short[outIndex];
		System.arraycopy(scratch, 0, output, 0, outIndex);
		return output;
	}

	/**
	 * Ganancia suave: sube microfonos bajos sin amplificar ruido de fondo.
	 * Solo sube, y lo hace despacio.
	 */
	private void updateGain(short[] frame) {
		double sum = 0.0;
		for (short sample : frame) {
			sum += (double) sample * sample;
		}
		float rms = (float) Math.sqrt(sum / Math.max(1, frame.length));
		if (rms < 60.0f) {
			return; // silencio: no tocamos la ganancia
		}
		float target = Math.max(1.0f, Math.min(6.0f, 2_200.0f / rms));
		framesSinceGain++;
		if (framesSinceGain >= 4) {
			framesSinceGain = 0;
			gain += (target - gain) * 0.15f;
		}
	}

	/** Convierte muestras cortas a bytes little endian (formato de Vosk). */
	public static byte[] toBytes(short[] samples) {
		byte[] bytes = new byte[samples.length * 2];
		for (int i = 0; i < samples.length; i++) {
			bytes[i * 2] = (byte) (samples[i] & 0xFF);
			bytes[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
		}
		return bytes;
	}
}
