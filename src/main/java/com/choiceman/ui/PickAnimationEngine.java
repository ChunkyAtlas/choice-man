package com.choiceman.ui;

import javax.sound.sampled.*;
import java.awt.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives the pick animation sequence for Choice Man:
 * <ul>
 *   <li>Particle burst/explosion effect for non-selected cards</li>
 *   <li>Lightweight audio playback for hover/popup/select SFX with a tiny clip pool</li>
 * </ul>
 * <p>
 * Stateless from the caller’s perspective; per-presentation state (particles and SFX volume)
 * is contained within the instance. Thread-safe for the typical render-thread + input-thread usage.
 */
final class PickAnimationEngine {
    private final Map<Integer, List<Particle>> particles = new ConcurrentHashMap<>();
    private volatile boolean particlesSeeded = false;
    private Sfx sfxHover, sfxPopup, sfxSelect;

    /**
     * Easing function (cubic-out) used by animations: starts fast, ends slow.
     *
     * @param t normalized time in [0, 1]
     * @return eased value in [0, 1]
     */
    static double cubicOut(double t) {
        double f = t - 1.0;
        return f * f * f + 1.0;
    }

    /**
     * Clamp a float value into the [0, 1] range.
     *
     * @param f value
     * @return clamped value
     */
    static float clamp01(float f) {
        return f < 0 ? 0 : (f > 1 ? 1 : f);
    }

    /**
     * Linear interpolation between two ints, returning a float.
     *
     * @param a start
     * @param b end
     * @param t normalized position [0, 1]
     * @return interpolated value
     */
    static float lerp(int a, int b, float t) {
        return (a + (b - a) * t);
    }

    /**
     * Load three short WAV effects and prepare small clip pools for overlapping playback.
     *
     * @param hoverRes  classpath resource path for hover SFX
     * @param popupRes  classpath resource path for popup SFX
     * @param selectRes classpath resource path for select SFX
     */
    void loadSfx(String hoverRes, String popupRes, String selectRes) {
        try {
            sfxHover = Sfx.load(hoverRes, 4);
        } catch (Exception ignored) {
        }
        try {
            sfxPopup = Sfx.load(popupRes, 6);
        } catch (Exception ignored) {
        }
        try {
            sfxSelect = Sfx.load(selectRes, 3);
        } catch (Exception ignored) {
        }
    }

    /**
     * Set the global volume for all SFX (0–100).
     *
     * @param p percentage (0 = muted, 100 = full)
     */
    void setVolumePercent(int p) {
        float v = clamp01(p / 100f);
        if (sfxHover != null) sfxHover.setVolume(v);
        if (sfxPopup != null) sfxPopup.setVolume(v);
        if (sfxSelect != null) sfxSelect.setVolume(v);
    }

    /**
     * Play the hover SFX if available.
     */
    void playHover() {
        if (sfxHover != null) sfxHover.play();
    }

    /**
     * Play the popup SFX if available.
     */
    void playPopup() {
        if (sfxPopup != null) sfxPopup.play();
    }

    /**
     * Play the select SFX if available.
     */
    void playSelect() {
        if (sfxSelect != null) sfxSelect.play();
    }

    /**
     * @return true when particle systems have been seeded for the current presentation.
     */
    boolean particlesSeeded() {
        return particlesSeeded;
    }

    /**
     * Clears all particle systems and marks them as not seeded.
     */
    void resetParticles() {
        particles.clear();
        particlesSeeded = false;
    }

    /**
     * Resets all internal per-run state (currently just particles).
     */
    void resetAll() {
        resetParticles();
    }

    /**
     * Seeds particle systems for each non-selected card. Call once right before the explosion
     * animation so that positions are based on the final layout.
     *
     * @param bounds list of card bounds in overlay space
     * @param selIdx selected card index; all others will emit particles
     */
    void seedParticles(List<Rectangle> bounds, int selIdx) {
        particles.clear();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < bounds.size(); i++) {
            if (i == selIdx) continue;
            Rectangle r = bounds.get(i);
            if (r == null) continue;
            int cx = r.x + r.width / 2, cy = r.y + r.height / 2;
            int count = 26 + rnd.nextInt(12);
            List<Particle> list = new ArrayList<>(count);
            for (int p = 0; p < count; p++) {
                double angle = rnd.nextDouble(0, Math.PI * 2), speed = 90 + rnd.nextDouble(90);
                float vx = (float) (Math.cos(angle) * speed), vy = (float) (Math.sin(angle) * speed - 40);
                float size = 2.0f + rnd.nextFloat() * 2.2f;
                int rshift = rnd.nextInt(30), gshift = rnd.nextInt(20);
                Color c = new Color(255, 200 + gshift, 40 + rshift, 255);
                list.add(new Particle(cx, cy, vx, vy, size, c));
            }
            particles.put(i, list);
        }
        particlesSeeded = true;
    }

    /**
     * Draws and advances particles for a single non-selected card, given the explosion timeline.
     *
     * @param g           graphics context
     * @param cardIndex   index of the card whose particles to draw
     * @param explodeNorm normalized time within the explosion [0..1]
     * @param explodeMs   total explosion duration in milliseconds
     */
    void drawParticles(Graphics2D g, int cardIndex, float explodeNorm, int explodeMs) {
        List<Particle> list = particles.get(cardIndex);
        if (list == null || list.isEmpty()) return;

        float grav = 120f;
        Composite old = g.getComposite();
        float t = clamp01(explodeNorm);
        float seconds = explodeMs / 1000f;

        for (Particle p : list) {
            float x = p.x0 + p.vx * t * seconds;
            float y = p.y0 + (p.vy * t * seconds) + 0.5f * grav * t * t;
            int alpha = Math.max(0, Math.min(255, (int) (255 * (1.0f - t))));
            if (alpha <= 0) continue;

            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha / 255f));
            g.setColor(new Color(p.color.getRed(), p.color.getGreen(), p.color.getBlue(), alpha));

            float size = Math.max(0.5f, p.size * (1.0f - t));
            int s = Math.max(1, Math.round(size));
            g.fillOval(Math.round(x) - s / 2, Math.round(y) - s / 2, s, s);
        }
        g.setComposite(old);
    }

    /**
     * Immutable particle parameters for a single dot in the explosion effect.
     */
    private static final class Particle {
        final float x0, y0, vx, vy, size;
        final Color color;

        /**
         * @param x0    origin x-position
         * @param y0    origin y-position
         * @param vx    initial x-velocity (px/s scaled by timeline)
         * @param vy    initial y-velocity (px/s scaled by timeline)
         * @param size  initial diameter in pixels
         * @param color particle color (alpha is animated over time)
         */
        Particle(float x0, float y0, float vx, float vy, float size, Color color) {
            this.x0 = x0;
            this.y0 = y0;
            this.vx = vx;
            this.vy = vy;
            this.size = size;
            this.color = color;
        }
    }

    /**
     * Tiny sound-effect helper with a small pool of {@link Clip}s to allow overlapping playbacks.
     * Accepts PCM or converts other encodings to PCM_SIGNED.
     */
    private static final class Sfx {
        private final List<Clip> pool = new ArrayList<>();
        private final byte[] wavBytes;
        private final int maxPool;
        private volatile float volumeLinear = 1f;

        private Sfx(byte[] wavBytes, int maxPool) {
            this.wavBytes = wavBytes;
            this.maxPool = Math.max(1, maxPool);
        }

        /**
         * Loads a WAV resource from the classpath, primes a single {@link Clip}, and prepares a pool.
         *
         * @param resourcePath classpath resource path to a small WAV file
         * @param maxPool      max number of clips to allocate lazily for overlap
         * @return Sfx instance
         * @throws Exception if the resource cannot be found or opened
         */
        static Sfx load(String resourcePath, int maxPool) throws Exception {
            try (InputStream in = ChoiceManOverlay.class.getResourceAsStream(resourcePath)) {
                if (in == null) throw new Exception("Missing SFX: " + resourcePath);
                byte[] bytes = in.readAllBytes();
                Sfx sfx = new Sfx(bytes, maxPool);
                Clip first = sfx.makeClip();
                sfx.applyVolume(first);
                synchronized (sfx.pool) {
                    sfx.pool.add(first);
                }
                return sfx;
            }
        }

        /**
         * Creates a {@link Clip} from the stored WAV bytes, converting to PCM if needed.
         */
        private Clip makeClip() throws Exception {
            try (AudioInputStream ais0 = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wavBytes))) {
                AudioFormat base = ais0.getFormat();
                AudioInputStream use = ais0;
                if (base.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
                    AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, base.getSampleRate(),
                            16, base.getChannels(), base.getChannels() * 2, base.getSampleRate(), false);
                    use = AudioSystem.getAudioInputStream(pcm, ais0);
                }
                Clip c = AudioSystem.getClip();
                c.open(use);
                return c;
            }
        }

        /**
         * Sets the linear volume (0..1) applied to all pooled clips.
         *
         * @param v linear gain (0 mutes). If MASTER_GAIN is available, converts to dB.
         */
        void setVolume(float v) {
            volumeLinear = v;
            synchronized (pool) {
                for (Clip c : pool) applyVolume(c);
            }
        }

        /**
         * Applies the current volume to a clip using MASTER_GAIN if present, otherwise VOLUME.
         */
        private void applyVolume(Clip c) {
            try {
                FloatControl gain = (FloatControl) c.getControl(FloatControl.Type.MASTER_GAIN);
                float db = (volumeLinear <= 0f) ? gain.getMinimum() : (float) (20.0 * Math.log10(volumeLinear));
                db = Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db));
                gain.setValue(db);
                return;
            } catch (IllegalArgumentException ignored) {
            }
            try {
                FloatControl vol = (FloatControl) c.getControl(FloatControl.Type.VOLUME);
                float vv = Math.max(vol.getMinimum(), Math.min(vol.getMaximum(), volumeLinear));
                vol.setValue(vv);
            } catch (IllegalArgumentException ignored) {
            }
        }

        /**
         * Plays one instance of the effect. Reuses an idle clip if available,
         * otherwise lazily allocates a new one up to {@code maxPool}.
         */
        void play() {
            try {
                Clip chosen = null;
                synchronized (pool) {
                    for (Clip c : pool)
                        if (!c.isRunning()) {
                            chosen = c;
                            break;
                        }
                    if (chosen == null && pool.size() < maxPool) {
                        chosen = makeClip();
                        applyVolume(chosen);
                        pool.add(chosen);
                    }
                }
                if (chosen != null) {
                    chosen.stop();
                    chosen.flush();
                    chosen.setFramePosition(0);
                    chosen.start();
                }
            } catch (Exception ignored) {
            }
        }
    }
}
