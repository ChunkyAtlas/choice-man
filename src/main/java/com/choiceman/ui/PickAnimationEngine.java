package com.choiceman.ui;

import net.runelite.client.audio.AudioPlayer;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives the pick animation sequence for Choice Man:
 * <ul>
 *   <li>Particle burst/explosion effect for non-selected cards</li>
 *   <li>Lightweight audio playback for hover/popup/select SFX through RuneLite's audio player</li>
 * </ul>
 * <p>
 * Stateless from the caller’s perspective; per-presentation state (particles and SFX volume)
 * is contained within the instance. Thread-safe for the typical render-thread + input-thread usage.
 */
final class PickAnimationEngine {
    private final Map<Integer, List<Particle>> particles = new ConcurrentHashMap<>();
    private final AudioPlayer audioPlayer;
    private volatile boolean particlesSeeded = false;
    private Sfx sfxHover, sfxPopup, sfxSelect;

    PickAnimationEngine(AudioPlayer audioPlayer) {
        this.audioPlayer = audioPlayer;
    }

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
     * Registers three short WAV effects for playback through RuneLite's audio player.
     */
    void loadSfx() {
        try {
            sfxHover = Sfx.load(audioPlayer, "/com/choiceman/sounds/CardMouseover.wav");
        } catch (Exception ignored) {
        }
        try {
            sfxPopup = Sfx.load(audioPlayer, "/com/choiceman/sounds/CardPopup.wav");
        } catch (Exception ignored) {
        }
        try {
            sfxSelect = Sfx.load(audioPlayer, "/com/choiceman/sounds/CardSelection.wav");
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
     * Sound-effect helper that delegates playback to RuneLite's {@link AudioPlayer}.
     */
    private static final class Sfx {
        private final AudioPlayer audioPlayer;
        private final String resourcePath;
        private volatile float volumeLinear = 1f;

        private Sfx(AudioPlayer audioPlayer, String resourcePath) {
            this.audioPlayer = audioPlayer;
            this.resourcePath = resourcePath;
        }

        /**
         * Verifies a WAV resource exists and creates a playback wrapper for it.
         *
         * @param resourcePath classpath resource path to a small WAV file
         * @return Sfx instance
         * @throws Exception if the resource cannot be found
         */
        static Sfx load(AudioPlayer audioPlayer, String resourcePath) throws Exception {
            if (ChoiceManOverlay.class.getResource(resourcePath) == null) {
                throw new Exception("Missing SFX: " + resourcePath);
            }

            return new Sfx(audioPlayer, resourcePath);
        }

        /**
         * Sets the linear volume (0..1) used for this sound effect.
         *
         * @param v linear gain (0 mutes).
         */
        void setVolume(float v) {
            volumeLinear = v;
        }

        /**
         * Converts the current linear volume to a decibel gain value for RuneLite audio playback.
         */
        private float gainDb() {
            if (volumeLinear <= 0f) {
                return -80.0f;
            }

            return (float) (20.0 * Math.log10(volumeLinear));
        }

        /**
         * Plays one instance of the effect through RuneLite's audio player.
         */
        void play() {
            try {
                if (volumeLinear <= 0f) {
                    return;
                }

                audioPlayer.play(ChoiceManOverlay.class, resourcePath, gainDb());
            } catch (Exception ignored) {
            }
        }
    }
}