package de.uni_marburg.schematch.utils;

/**
 * NumPy's default generator, {@code numpy.random.default_rng(seed)}: SeedSequence seeding of a
 * PCG64 (XSL-RR 128/64) bit generator, with {@code random()} and scalar-bound {@code uniform()}.
 *
 * <p>Reimplemented so that seeded Java wrappers reproduce draws made by the original Python
 * experiments value for value. Only a non-negative integer seed below 2^64 is supported, which
 * covers seeds derived from 16 hex digits of a hash. Checked against NumPy 2.2 in the tests.
 */
public final class NumpyRandom {
    private static final int INIT_A = 0x43b0d7e5;
    private static final int MULT_A = 0x931e8875;
    private static final int INIT_B = 0x8b51f9dd;
    private static final int MULT_B = 0x58f38ded;
    private static final int MIX_MULT_L = 0xca01f9dd;
    private static final int MIX_MULT_R = 0x4973f715;
    private static final long MULT_HI = 2549297995355413924L;
    private static final long MULT_LO = 4865540595714422341L;

    private long stateHi, stateLo;
    private final long incHi, incLo;

    /** Equivalent to {@code numpy.random.default_rng(seed)}, seed read as an unsigned 64-bit integer. */
    public NumpyRandom(long seed) {
        int[] entropy = (seed >>> 32) == 0 ? new int[]{(int) seed} : new int[]{(int) seed, (int) (seed >>> 32)};
        int[] pool = mixEntropy(entropy);
        int[] words = new int[8];
        int hashConst = INIT_B;
        for (int i = 0; i < words.length; i++) {
            int value = pool[i % pool.length];
            value ^= hashConst;
            hashConst *= MULT_B;
            value *= hashConst;
            value ^= value >>> 16;
            words[i] = value;
        }
        // generate_state(4, uint64): little-endian word pairs; seed = (s0 << 64) | s1, inc = (s2 << 64) | s3
        long s0 = pack(words[0], words[1]), s1 = pack(words[2], words[3]);
        long s2 = pack(words[4], words[5]), s3 = pack(words[6], words[7]);
        incHi = (s2 << 1) | (s3 >>> 63);
        incLo = (s3 << 1) | 1L;
        stateHi = 0;
        stateLo = 0;
        step();
        long lo = stateLo + s1;
        stateHi = stateHi + s0 + (Long.compareUnsigned(lo, stateLo) < 0 ? 1 : 0);
        stateLo = lo;
        step();
    }

    private static long pack(int low, int high) {
        return (Integer.toUnsignedLong(high) << 32) | Integer.toUnsignedLong(low);
    }

    private static int hashmix(int value, int[] hashConst) {
        value ^= hashConst[0];
        hashConst[0] *= MULT_A;
        value *= hashConst[0];
        return value ^ (value >>> 16);
    }

    private static int mix(int x, int y) {
        int result = MIX_MULT_L * x - MIX_MULT_R * y;
        return result ^ (result >>> 16);
    }

    private static int[] mixEntropy(int[] entropy) {
        int[] mixer = new int[4];
        int[] hashConst = {INIT_A};
        for (int i = 0; i < mixer.length; i++) {
            mixer[i] = hashmix(i < entropy.length ? entropy[i] : 0, hashConst);
        }
        for (int src = 0; src < mixer.length; src++) {
            for (int dst = 0; dst < mixer.length; dst++) {
                if (src != dst) {
                    mixer[dst] = mix(mixer[dst], hashmix(mixer[src], hashConst));
                }
            }
        }
        for (int src = mixer.length; src < entropy.length; src++) {
            for (int dst = 0; dst < mixer.length; dst++) {
                mixer[dst] = mix(mixer[dst], hashmix(entropy[src], hashConst));
            }
        }
        return mixer;
    }

    /** state = state * MULT + inc (mod 2^128). */
    private void step() {
        long hi = Math.unsignedMultiplyHigh(stateLo, MULT_LO) + stateLo * MULT_HI + stateHi * MULT_LO;
        long lo = stateLo * MULT_LO;
        long sum = lo + incLo;
        stateHi = hi + incHi + (Long.compareUnsigned(sum, lo) < 0 ? 1 : 0);
        stateLo = sum;
    }

    public long nextLong() {
        step();
        return Long.rotateRight(stateHi ^ stateLo, (int) (stateHi >>> 58));
    }

    /** {@code Generator.random()}: 53 random bits in [0, 1). */
    public double random() {
        return (nextLong() >>> 11) * (1.0 / 9007199254740992.0);
    }

    /** {@code Generator.uniform(low, high)} for scalar bounds; arrays draw in C order. */
    public double uniform(double low, double high) {
        return low + (high - low) * random();
    }
}
