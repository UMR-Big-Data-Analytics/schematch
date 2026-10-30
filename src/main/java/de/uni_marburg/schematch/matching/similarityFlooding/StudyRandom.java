package de.uni_marburg.schematch.matching.similarityFlooding;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * The two random number generators the SF study drew its controls from, so that the native generators reproduce
 * the study's edge files exactly rather than only in distribution.
 *
 * <p>{@link Python} is CPython's {@code random.Random} (MT19937 with {@code init_by_array}, string seeds of version
 * 2, {@code _randbelow} by rejection on {@code getrandbits}, {@code shuffle} and {@code sample}). {@link Numpy} is
 * {@code numpy.random.default_rng(int)}: a {@code SeedSequence} feeding PCG64 (XSL-RR 128/64), with
 * {@code random()}, and {@code permutation(n)} through the bounded {@code random_interval} draw.
 */
final class StudyRandom {
    private StudyRandom() {}

    static String sha256Hex(String text) {
        return HexFormat.of().formatHex(digest("SHA-256", text.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] digest(String algorithm, byte[] bytes) {
        try {
            return MessageDigest.getInstance(algorithm).digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** CPython's random.Random. */
    static final class Python {
        private static final int N = 624, M = 397;
        private final int[] mt = new int[N];
        private int index;

        /** random.Random(text): seed version 2, int.from_bytes(text + sha512(text)). */
        Python(String text) {
            byte[] raw = text.getBytes(StandardCharsets.UTF_8);
            byte[] sha = digest("SHA-512", raw);
            byte[] all = new byte[raw.length + sha.length];
            System.arraycopy(raw, 0, all, 0, raw.length);
            System.arraycopy(sha, 0, all, raw.length, sha.length);
            seed(new BigInteger(1, all));
        }

        Python(BigInteger value) {
            seed(value.abs());
        }

        private void seed(BigInteger n) {
            int words = Math.max(1, (n.bitLength() + 31) / 32);
            int[] key = new int[words];
            for (int i = 0; i < words; i++) key[i] = n.shiftRight(32 * i).intValue();
            initByArray(key);
        }

        private void initGenrand(int s) {
            mt[0] = s;
            for (int i = 1; i < N; i++) {
                mt[i] = 1812433253 * (mt[i - 1] ^ (mt[i - 1] >>> 30)) + i;
            }
            index = N;
        }

        private void initByArray(int[] key) {
            initGenrand(19650218);
            int i = 1, j = 0;
            for (int k = Math.max(N, key.length); k > 0; k--) {
                mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1664525)) + key[j] + j;
                i++;
                j++;
                if (i >= N) { mt[0] = mt[N - 1]; i = 1; }
                if (j >= key.length) j = 0;
            }
            for (int k = N - 1; k > 0; k--) {
                mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1566083941)) - i;
                i++;
                if (i >= N) { mt[0] = mt[N - 1]; i = 1; }
            }
            mt[0] = 0x80000000;
        }

        /** genrand_uint32, as an unsigned value in an int. */
        int nextInt32() {
            if (index >= N) {
                for (int k = 0; k < N; k++) {
                    int y = (mt[k] & 0x80000000) | (mt[(k + 1) % N] & 0x7fffffff);
                    mt[k] = mt[(k + M) % N] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
                }
                index = 0;
            }
            int y = mt[index++];
            y ^= y >>> 11;
            y ^= (y << 7) & 0x9d2c5680;
            y ^= (y << 15) & 0xefc60000;
            y ^= y >>> 18;
            return y;
        }

        /** getrandbits(k) for 0 < k <= 31. */
        int getrandbits(int k) {
            return nextInt32() >>> (32 - k);
        }

        /** _randbelow_with_getrandbits(n) for 0 < n < 2^31. */
        int randbelow(int n) {
            int k = 32 - Integer.numberOfLeadingZeros(n);
            int r = getrandbits(k);
            while (r >= n) r = getrandbits(k);
            return r;
        }

        <T> void shuffle(List<T> x) {
            for (int i = x.size() - 1; i > 0; i--) {
                int j = randbelow(i + 1);
                T tmp = x.get(i);
                x.set(i, x.get(j));
                x.set(j, tmp);
            }
        }

        /** random.sample(population, k), both of CPython's branches (pool and set). */
        <T> List<T> sample(List<T> population, int k) {
            int n = population.size();
            if (k < 0 || k > n) throw new IllegalArgumentException("Sample larger than population or is negative");
            List<T> result = new ArrayList<>(k);
            int setsize = 21;
            if (k > 5) setsize += (int) Math.pow(4, Math.ceil(Math.log(k * 3) / Math.log(4)));
            if (n <= setsize) {
                List<T> pool = new ArrayList<>(population);
                for (int i = 0; i < k; i++) {
                    int j = randbelow(n - i);
                    result.add(pool.get(j));
                    pool.set(j, pool.get(n - i - 1));
                }
            } else {
                Set<Integer> selected = new HashSet<>();
                for (int i = 0; i < k; i++) {
                    int j = randbelow(n);
                    while (selected.contains(j)) j = randbelow(n);
                    selected.add(j);
                    result.add(population.get(j));
                }
            }
            return result;
        }
    }

    /** numpy.random.default_rng(seed) for a non-negative integer seed: SeedSequence and PCG64. */
    static final class Numpy {
        private static final long MULT_HIGH = 2549297995355413924L, MULT_LOW = 4865540595714422341L;
        private long stateHigh, stateLow, incHigh, incLow;
        private boolean hasUint32;
        private int uinteger;

        Numpy(BigInteger seed) {
            int[] entropy = uint32Words(seed);
            int[] pool = mixEntropy(entropy);
            int[] words = generateState(pool, 8);
            long s0 = unsigned64(words[0], words[1]), s1 = unsigned64(words[2], words[3]);
            long i0 = unsigned64(words[4], words[5]), i1 = unsigned64(words[6], words[7]);
            // pcg_setseq_128_srandom_r(state, s0:s1, i0:i1)
            stateHigh = 0;
            stateLow = 0;
            incHigh = (i0 << 1) | (i1 >>> 63);
            incLow = (i1 << 1) | 1L;
            step();
            long low = stateLow + s1;
            long carry = Long.compareUnsigned(low, stateLow) < 0 ? 1 : 0;
            stateHigh = stateHigh + s0 + carry;
            stateLow = low;
            step();
        }

        Numpy(long seed) {
            this(BigInteger.valueOf(seed));
        }

        private static long unsigned64(int low, int high) {
            return (Integer.toUnsignedLong(high) << 32) | Integer.toUnsignedLong(low);
        }

        private static int[] uint32Words(BigInteger n) {
            if (n.signum() < 0) throw new IllegalArgumentException("negative seed");
            if (n.signum() == 0) return new int[]{0};
            int words = (n.bitLength() + 31) / 32;
            int[] out = new int[words];
            for (int i = 0; i < words; i++) out[i] = n.shiftRight(32 * i).intValue();
            return out;
        }

        private static final int INIT_A = 0x43b0d7e5, MULT_A = 0x931e8875, INIT_B = 0x8b51f9dd, MULT_B = 0x58f38ded;
        private static final int MIX_MULT_L = 0xca01f9dd, MIX_MULT_R = 0x4973f715;

        private static int hashmix(int value, int[] hashConst) {
            value ^= hashConst[0];
            hashConst[0] *= MULT_A;
            value *= hashConst[0];
            value ^= value >>> 16;
            return value;
        }

        private static int mix(int x, int y) {
            int result = MIX_MULT_L * x - MIX_MULT_R * y;
            result ^= result >>> 16;
            return result;
        }

        private static int[] mixEntropy(int[] entropy) {
            int[] mixer = new int[4];
            int[] hashConst = {INIT_A};
            for (int i = 0; i < mixer.length; i++) {
                mixer[i] = hashmix(i < entropy.length ? entropy[i] : 0, hashConst);
            }
            for (int src = 0; src < mixer.length; src++) {
                for (int dst = 0; dst < mixer.length; dst++) {
                    if (src != dst) mixer[dst] = mix(mixer[dst], hashmix(mixer[src], hashConst));
                }
            }
            for (int src = mixer.length; src < entropy.length; src++) {
                for (int dst = 0; dst < mixer.length; dst++) {
                    mixer[dst] = mix(mixer[dst], hashmix(entropy[src], hashConst));
                }
            }
            return mixer;
        }

        private static int[] generateState(int[] pool, int words) {
            int hashConst = INIT_B;
            int[] out = new int[words];
            for (int i = 0; i < words; i++) {
                int v = pool[i % pool.length];
                v ^= hashConst;
                hashConst *= MULT_B;
                v *= hashConst;
                v ^= v >>> 16;
                out[i] = v;
            }
            return out;
        }

        /** state = state * MULT + inc, in 128 bits. */
        private void step() {
            long low = stateLow * MULT_LOW;
            long high = Math.unsignedMultiplyHigh(stateLow, MULT_LOW) + stateLow * MULT_HIGH + stateHigh * MULT_LOW;
            long sum = low + incLow;
            long carry = Long.compareUnsigned(sum, low) < 0 ? 1 : 0;
            stateHigh = high + incHigh + carry;
            stateLow = sum;
        }

        long nextUint64() {
            step();
            long xored = stateHigh ^ stateLow;
            int rot = (int) (stateHigh >>> 58);
            return Long.rotateRight(xored, rot);
        }

        int nextUint32() {
            if (hasUint32) {
                hasUint32 = false;
                return uinteger;
            }
            long next = nextUint64();
            hasUint32 = true;
            uinteger = (int) (next >>> 32);
            return (int) next;
        }

        /** Generator.random(): 53 random bits in [0, 1). */
        double random() {
            return (nextUint64() >>> 11) * (1.0 / 9007199254740992.0);
        }

        /** random_interval(max) for 0 <= max < 2^32: masked rejection on 32-bit draws. */
        long interval(long max) {
            if (max == 0) return 0;
            long mask = max;
            mask |= mask >>> 1;
            mask |= mask >>> 2;
            mask |= mask >>> 4;
            mask |= mask >>> 8;
            mask |= mask >>> 16;
            mask |= mask >>> 32;
            if (max > 0xffffffffL) throw new IllegalArgumentException("interval above 2^32 is not needed here");
            long value;
            while ((value = Integer.toUnsignedLong(nextUint32()) & mask) > max) {
                // rejected
            }
            return value;
        }

        /** Generator.permutation(n): arange(n) shuffled from the back. */
        int[] permutation(int n) {
            int[] out = new int[n];
            for (int i = 0; i < n; i++) out[i] = i;
            for (int i = n - 1; i >= 1; i--) {
                int j = (int) interval(i);
                int tmp = out[j];
                out[j] = out[i];
                out[i] = tmp;
            }
            return out;
        }
    }
}
