package dev.experimental.edgetts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Sec-MS-GEC v2 de rany2/edge-tts 7.3.1: CS64 + SHA-256 con el estado
 * inicial recortado de Edge. No es SHA-256 estándar.
 */
final class SecMsGecV2 {
    static final long M = 0xFFFFFFFFL;
    static long b(long v) { return v & M; }
    static long rol16(long value) {
        long v = b(value);
        return b((v << 16) | (v >>> 16));
    }
    static final long[] K = {
        0x428A2F98L,0x71374491L,0xB5C0FBCFL,0xE9B5DBA5L,0x3956C25BL,0x59F111F1L,0x923F82A4L,0xAB1C5ED5L,
        0xD807AA98L,0x12835B01L,0x243185BEL,0x550C7DC3L,0x72BE5D74L,0x80DEB1FEL,0x9BDC06A7L,0xC19BF174L,
        0xE49B69C1L,0xEFBE4786L,0x0FC19DC6L,0x240CA1CCL,0x2DE92C6FL,0x4A7484AAL,0x5CB0A9DCL,0x76F988DAL,
        0x983E5152L,0xA831C66DL,0xB00327C8L,0xBF597FC7L,0xC6E00BF3L,0xD5A79147L,0x06CA6351L,0x14292967L,
        0x27B70A85L,0x2E1B2138L,0x4D2C6DFCL,0x53380D13L,0x650A7354L,0x766A0ABBL,0x81C2C92EL,0x92722C85L,
        0xA2BFE8A1L,0xA81A664BL,0xC24B8B70L,0xC76C51A3L,0xD192E819L,0xD6990624L,0xF40E3585L,0x106AA070L,
        0x19A4C116L,0x1E376C08L,0x2748774CL,0x34B0BCB5L,0x391C0CB3L,0x4ED8AA4AL,0x5B9CCA4FL,0x682E6FF3L,
        0x748F82EEL,0x78A5636FL,0x84C87814L,0x8CC70208L,0x90BEFFFAL,0xA4506CEBL,0xBEF9A3F7L,0xC67178F2L
    };
    static final long ADJ = 0xC9DC06A7L;
    static final long[] KB = Arrays.copyOf(K, 64);
    static {
        KB[14] = ADJ;
    }
    static final long[] INIT = {
        0x6A09E667L,0xBB67AE85L,0x3C6EF372L,0xA54FF53AL,0x510E527FL,0x9B05688CL,0x1F83D9ABL,0x5BE0CD19L
    };
    static final long[] STATE0 = {
        0xFD9FBEA2L,0x91243CE7L,0xBE499094L,0xCD2E1E0AL,0x6C006F49L,0x04BD35ABL,0xFEB0A7E4L,0x03EFFBD2L
    };
    static final long[] SCHED = {
        0xEB24BF9EL,0x1558691FL,0xA0C09EE2L,0x0CFA32BAL,0xA622EE8CL,0xD1DD9491L,0xC0DD25A0L,0xF9E70AB6L,
        0xF0C31573L,0x7058CE4EL,0x7188E606L,0x00E5AB33L,0xD258B060L,0x72766564L
    };
    static final long S0A = 0x059C0B80L;
    static final long S1A = 0x000B92C0L;
    static final long[][] CS = {
        {0xE82E91B1L,0xB32700D0L},{0x44DE24C6L,0x88DFF51DL},{0x3BEB65BCL,0x29514038L},{0xC89C5933L,0xB328CBDFL},
        {0x8D1EC211L,0xD405B4D5L},{0x314EF5D5L,0x26E55F3CL},{0xE92491B1L,0x607801C6L},{0xF0A5B9D5L,0x82B2C1FBL},
        {0x06064CF3L,0xFA51A3DAL},{0xDA79FA60L,0x207CC464L},{0xBED20560L,0x3C3C74F3L},{0x89F9C9F8L,0xD2D14B61L},
        {0x35FA38C6L,0xBB04A2C1L},{0xCB49E3C6L,0xC5626167L}
    };
    static final long CS_FACTOR = 0x82893933L;
    static final long CS_A0 = 0xF2F90B1AL;
    static final long CS_A1 = 0x0B1A0000L;

    static long sigma0(long value) {
        long v = b(value);
        return b((v >>> 7) ^ (v << 25) ^ (v >>> 18) ^ (v << 14) ^ (v >>> 3));
    }
    static long sigma1(long value) {
        long v = b(value);
        return b((v >>> 17) ^ (v << 15) ^ (v >>> 19) ^ (v << 13) ^ (v >>> 10));
    }
    static long dup(long e) { return b(e) * 0x100000001L; }

    static long[] rounds(long[] state, ArrayList<Long> words, int first) {
        long a=state[0],b0=state[1],c=state[2],d=state[3],e=state[4],f=state[5],g=state[6],h=state[7];
        long[] constants = first == 14 ? KB : K;
        int aligned = Math.min(64, ((first + 7) / 8) * 8);
        for (int index = first; index < aligned; index++) {
            long rotE = dup(e), rotA = dup(a);
            long t1 = h + ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25))
                + (g ^ (e & (f ^ g))) + constants[index] + words.get(index);
            long t2 = ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((a & b0) | (c & (a | b0)));
            long na = b(t1 + t2);
            long nd = b(d + t1);
            h = g; g = f; f = e; e = nd; d = c; c = b0; b0 = a; a = na;
        }
        for (int index = aligned; index < 64; index += 8) {
            long rotE = dup(e), rotA = dup(a);
            long t1 = h + ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25))
                + (g ^ (e & (f ^ g))) + constants[index] + words.get(index);
            d = b(d + t1);
            h = b(t1 + ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((a & b0) | (c & (a | b0))));
            rotE = dup(d); rotA = dup(h);
            t1 = g + ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25))
                + (f ^ (d & (e ^ f))) + constants[index + 1] + words.get(index + 1);
            c = b(c + t1);
            g = b(t1 + ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((h & a) | (b0 & (h | a))));
            rotE = dup(c); rotA = dup(g);
            t1 = f + ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25))
                + (e ^ (c & (d ^ e))) + constants[index + 2] + words.get(index + 2);
            b0 = b(b0 + t1);
            f = b(t1 + ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((g & h) | (a & (g | h))));
            rotE = dup(b0); rotA = dup(f);
            t1 = e + ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25))
                + (d ^ (b0 & (c ^ d))) + constants[index + 3] + words.get(index + 3);
            a = b(a + t1);
            e = b(t1 + ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((f & g) | (h & (f | g))));
            rotE = dup(a); rotA = dup(e);
            t1 = d + ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25))
                + (c ^ (a & (b0 ^ c))) + constants[index + 4] + words.get(index + 4);
            h = b(h + t1);
            d = b(t1 + ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((e & f) | (g & (e | f))));
            rotE = dup(h); rotA = dup(d);
            t1 = c + ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25))
                + (b0 ^ (h & (a ^ b0))) + constants[index + 5] + words.get(index + 5);
            g = b(g + t1);
            c = b(t1 + ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((d & e) | (f & (d | e))));
            rotE = dup(g); rotA = dup(c);
            t1 = b0 + ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25))
                + (a ^ (g & (h ^ a))) + constants[index + 6] + words.get(index + 6);
            f = b(f + t1);
            b0 = b(t1 + ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((c & d) | (e & (c | d))));
            rotE = dup(f); rotA = dup(b0);
            t1 = a + ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25))
                + (h ^ (f & (g ^ h))) + constants[index + 7] + words.get(index + 7);
            e = b(e + t1);
            a = b(t1 + ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((b0 & c) | (d & (b0 | c))));
        }
        return new long[]{a,b0,c,d,e,f,g,h};
    }

    static void expand(ArrayList<Long> words, int first) {
        for (int index = first; index < 64; index++) {
            long x = words.get(index - 2), y = words.get(index - 15);
            long s1 = (x >>> 17) ^ (x << 15) ^ (x >>> 19) ^ (x << 13) ^ (x >>> 10);
            long s0 = (y >>> 7) ^ (y << 25) ^ (y >>> 18) ^ (y << 14) ^ (y >>> 3);
            words.add(b(s1 + words.get(index - 7) + s0 + words.get(index - 16)));
        }
    }

    static long[] shaInit(byte[] data) {
        ArrayList<Long> words = new ArrayList<>();
        for (int i = 0; i < 14; i++) words.add(0L);
        words.add(((data[0] & 255L) << 16) | ((data[1] & 255L) << 8) | (data[2] & 255L));
        words.add(((data[3] & 255L) << 24) | ((data[4] & 255L) << 16) | ((data[5] & 255L) << 8) | (data[6] & 255L));
        for (int index = 16; index < 30; index++) {
            long value = SCHED[index - 16];
            if (index - 2 >= 14) value += (index == 16 ? (sigma1(words.get(index - 2)) ^ S1A) : sigma1(words.get(index - 2)));
            if (index - 7 >= 14) value += words.get(index - 7);
            if (index - 15 >= 14) value += (index == 29 ? (sigma0(words.get(index - 15)) ^ S0A) : sigma0(words.get(index - 15)));
            words.add(b(value));
        }
        words.add(b(sigma1(words.get(28)) + words.get(23) + sigma0(words.get(15)) + words.get(14) + ADJ - K[14]));
        expand(words, 31);
        long[] working = rounds(STATE0, words, 14);
        long[] out = new long[8];
        for (int i = 0; i < 8; i++) out[i] = b(INIT[i] + working[i]);
        return out;
    }

    static byte[] shaDigest(byte[] data) {
        int length = 57 + data.length;
        int pad = Math.floorMod(55 - length, 64);
        byte[] rest = Arrays.copyOfRange(data, 7, data.length);
        byte[] padded = new byte[rest.length + 1 + pad + 8];
        System.arraycopy(rest, 0, padded, 0, rest.length);
        padded[rest.length] = (byte) 0x80;
        long bits = (long) length * 8L;
        for (int i = 0; i < 8; i++) padded[padded.length - 1 - i] = (byte) (bits >>> (8 * i));
        long[] state = shaInit(Arrays.copyOfRange(data, 0, 7));
        for (int offset = 0; offset < padded.length; offset += 64) {
            ArrayList<Long> words = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                int o = offset + i * 4;
                words.add(((padded[o] & 255L) << 24) | ((padded[o+1] & 255L) << 16) | ((padded[o+2] & 255L) << 8) | (padded[o+3] & 255L));
            }
            expand(words, 16);
            long[] working = rounds(state, words, 0);
            for (int i = 0; i < 8; i++) state[i] = b(state[i] + working[i]);
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            long v = state[i];
            out[i*4] = (byte) (v >>> 24);
            out[i*4+1] = (byte) (v >>> 16);
            out[i*4+2] = (byte) (v >>> 8);
            out[i*4+3] = (byte) v;
        }
        return out;
    }

    static long le32(byte[] d, int o) {
        return (d[o] & 255L) | ((d[o+1] & 255L) << 8) | ((d[o+2] & 255L) << 16) | ((d[o+3] & 255L) << 24);
    }
    static ArrayList<Long> wordsLe(byte[] data) {
        int n = (data.length + 3) & ~3;
        byte[] padded = Arrays.copyOf(data, n);
        ArrayList<Long> words = new ArrayList<>();
        for (int i = 0; i < n; i += 4) words.add(le32(padded, i));
        if ((words.size() & 1) == 1) words.add(0L);
        return words;
    }
    static long[] cs64(byte[] data, byte[] digest) {
        long keyA = le32(digest, 0) | 1L;
        long keyB = le32(digest, 4) | 1L;
        long[] factors = { b(keyA * CS_FACTOR), b(keyB * CS_FACTOR) };
        ArrayList<Long> terms = new ArrayList<>();
        ArrayList<Long> offsets = new ArrayList<>();
        for (int i = 0; i < CS.length; i++) {
            terms.add(b(CS[i][0] * factors[i % 2]));
            offsets.add(CS[i][1]);
        }
        byte[] prefixed = new byte[data.length + 1];
        System.arraycopy(data, 0, prefixed, 1, data.length);
        ArrayList<Long> ws = wordsLe(prefixed);
        for (int index = 0; index < ws.size(); index++) {
            long word = ws.get(index);
            long term = word * (index % 2 == 0 ? keyA : keyB);
            long offset = rol16(word) * (index % 2 == 0 ? 0xEF0569FBL : 0xC31713DBL);
            if (index == 0) {
                term += CS_A0 * factors[0];
                offset += CS_A1;
            }
            terms.add(b(term));
            offsets.add(b(offset));
        }
        long swapAcc = 0, swapLast = 0, state = 0, acc = 0;
        for (int index = 0; index < terms.size(); index += 2) {
            long x = terms.get(index), y = terms.get(index + 1);
            long t = rol16(b(offsets.get(index) + x));
            long q = b(t * 0x76F50F34L);
            swapAcc += b(rol16(q) * 0xC3EFEA97L + q);
            t = rol16(b(offsets.get(index + 1) + y));
            q = b(t * 0x1881CE3CL);
            swapLast = b(rol16(q) * 0x35BD1EC9L + q);
            swapAcc = b(swapAcc + swapLast);
            state = b(state * keyA + x);
            state = b(rol16(state) * 0xCF98B111L);
            state = b(rol16(state) * 0x87085B9FL);
            state = b(rol16(state) * 0x12CEB96DL);
            state = b(rol16(state) * 0x257E1D83L);
            acc += state;
            t = b(state * keyB + y);
            t = b(rol16(t) * 0xA27416F5L);
            t = b(rol16(t) * 0xD38396FFL);
            state = b(rol16(b(rol16(t) * 0x7C932B89L)) * 0xBFA49F69L);
            acc = b(acc + state);
        }
        return new long[]{ b(swapLast ^ state), b(swapAcc ^ acc) };
    }
    static String generate(long unixSeconds, String trustedClientToken) {
        long ticks = unixSeconds + 11644473600L;
        ticks -= ticks % 300L;
        ticks *= 10000000L;
        byte[] raw = (ticks + trustedClientToken).getBytes(StandardCharsets.US_ASCII);
        byte[] digest = shaDigest(raw);
        long[] cs = cs64(raw, digest);
        byte[] packed = new byte[8];
        for (int w = 0; w < 2; w++) {
            long v = cs[w];
            packed[w * 4] = (byte) v;
            packed[w * 4 + 1] = (byte) (v >>> 8);
            packed[w * 4 + 2] = (byte) (v >>> 16);
            packed[w * 4 + 3] = (byte) (v >>> 24);
        }
        return hex(packed) + "-" + hex(digest);
    }

    private static String hex(byte[] d) {
        StringBuilder s = new StringBuilder();
        for (byte v : d) s.append(String.format("%02X", v));
        return s.toString();
    }
}
