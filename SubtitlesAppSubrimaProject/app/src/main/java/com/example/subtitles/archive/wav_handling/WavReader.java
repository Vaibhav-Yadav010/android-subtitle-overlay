package com.example.subtitles.archive.wav_handling;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class WavReader {
    /** Reads a PCM WAV file without assuming a fixed 44-byte header. */
    public static short[] read16bitMono(File wavFile) throws IOException {
        try (FileInputStream in = new FileInputStream(wavFile)) {
            byte[] riff = new byte[12];
            readFully(in, riff, 0, riff.length);
            if (!ascii(riff, 0, 4).equals("RIFF") || !ascii(riff, 8, 4).equals("WAVE")) {
                throw new IOException("Invalid WAV RIFF/WAVE header");
            }

            boolean validPcmMono16 = false;
            byte[] data = null;

            while (true) {
                byte[] chunkHeader = new byte[8];
                int first = in.read(chunkHeader, 0, chunkHeader.length);
                if (first == -1) break;
                if (first != chunkHeader.length) {
                    throw new IOException("Truncated WAV chunk header");
                }

                String chunkId = ascii(chunkHeader, 0, 4);
                long chunkSize = uint32LE(chunkHeader, 4);
                if (chunkSize > Integer.MAX_VALUE) {
                    throw new IOException("WAV chunk is too large");
                }

                if ("fmt ".equals(chunkId)) {
                    if (chunkSize < 16) throw new IOException("Invalid WAV fmt chunk");
                    byte[] fmt = new byte[(int) chunkSize];
                    readFully(in, fmt, 0, fmt.length);
                    int audioFormat = uint16LE(fmt, 0);
                    int channels = uint16LE(fmt, 2);
                    int bitsPerSample = uint16LE(fmt, 14);
                    if (audioFormat != 1 || channels != 1 || bitsPerSample != 16) {
                        throw new IOException("WAV is not 16-bit PCM mono");
                    }
                    validPcmMono16 = true;
                } else if ("data".equals(chunkId)) {
                    data = new byte[(int) chunkSize];
                    readFully(in, data, 0, data.length);
                } else {
                    skipFully(in, chunkSize);
                }

                // RIFF chunks are word-aligned; a data size of odd length has one padding byte.
                if ((chunkSize & 1L) != 0) {
                    skipFully(in, 1);
                }
            }

            if (!validPcmMono16) throw new IOException("WAV fmt chunk missing or unsupported");
            if (data == null) throw new IOException("WAV data chunk missing");
            if ((data.length & 1) != 0) throw new IOException("WAV data chunk has odd byte length");

            short[] samples = new short[data.length / 2];
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
            return samples;
        }
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int uint16LE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static long uint32LE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    private static void readFully(InputStream in, byte[] buffer, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int read = in.read(buffer, offset + total, length - total);
            if (read == -1) throw new IOException("Unexpected end of WAV file");
            total += read;
        }
    }

    private static void skipFully(InputStream in, long length) throws IOException {
        while (length > 0) {
            long skipped = in.skip(length);
            if (skipped > 0) {
                length -= skipped;
                continue;
            }
            if (in.read() == -1) throw new IOException("Unexpected end of WAV file");
            length--;
        }
    }
}
