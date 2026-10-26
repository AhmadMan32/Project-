// JARVIS orb: çok katmanlı, ışıklı, sürekli canlı. Modlar:
// idle (yavaş dönüş, hafif nabız), listening (mikrofona tepki),
// thinking (hızlanan, enerji biriken), speaking (yayılan halkalar, ses ritmi).
// "level" gerçek mikrofon / ses seviyesidir; konuşma süresi gerçek sesin
// başlangıç-bitiş olaylarından gelir.

import { memo, useEffect, useMemo } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import Animated, {
  Easing,
  SharedValue,
  useAnimatedStyle,
  useFrameCallback,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';
import Svg, { Circle, Defs, Ellipse, G, Line, Path, RadialGradient, Stop } from 'react-native-svg';
import { C } from './theme';

export type OrbMode = 'idle' | 'listening' | 'thinking' | 'speaking';

const MODE = {
  idle: { speed: 1, energy: 0.15, ripple: 0, rippleRate: 0.25 },
  listening: { speed: 1.6, energy: 0.5, ripple: 0.45, rippleRate: 0.5 },
  thinking: { speed: 4.2, energy: 0.8, ripple: 0.15, rippleRate: 0.35 },
  speaking: { speed: 2, energy: 1, ripple: 1, rippleRate: 0.85 },
} as const;

function arc(cx: number, cy: number, r: number, a0: number, a1: number) {
  const p = (a: number) => [cx + r * Math.cos((a * Math.PI) / 180), cy + r * Math.sin((a * Math.PI) / 180)];
  const [x0, y0] = p(a0);
  const [x1, y1] = p(a1);
  return `M ${x0} ${y0} A ${r} ${r} 0 ${a1 - a0 > 180 ? 1 : 0} 1 ${x1} ${y1}`;
}

function Orb({ size, mode, level }: { size: number; mode: OrbMode; level: SharedValue<number> }) {
  const S = size;
  const c = S / 2;
  const clock = useSharedValue(0);
  const phase = useSharedValue(0);
  const lvl = useSharedValue(0);
  const speed = useSharedValue(1);
  const energy = useSharedValue(0.15);
  const ripple = useSharedValue(0);
  const rippleRate = useSharedValue(0.25);
  const ripplePhase = useSharedValue(0);

  useEffect(() => {
    const m = MODE[mode];
    const cfg = { duration: mode === 'idle' ? 1400 : 500, easing: Easing.inOut(Easing.cubic) };
    speed.value = withTiming(m.speed, cfg);
    energy.value = withTiming(m.energy, cfg);
    ripple.value = withTiming(m.ripple, cfg);
    rippleRate.value = withTiming(m.rippleRate, cfg);
  }, [mode, speed, energy, ripple, rippleRate]);

  useFrameCallback((f) => {
    'worklet';
    const dt = Math.min(0.05, (f.timeSincePreviousFrame ?? 16) / 1000);
    clock.value += dt;
    phase.value += dt * speed.value;
    ripplePhase.value += dt * rippleRate.value;
    const target = level.value;
    lvl.value += (target - lvl.value) * (target > lvl.value ? 0.45 : 0.12);
  });

  const halo = useAnimatedStyle(() => ({
    opacity: Math.min(1, 0.32 + 0.38 * energy.value + 0.35 * lvl.value + 0.07 * Math.sin(clock.value * 1.3)),
    transform: [{ scale: 1 + 0.025 * Math.sin(clock.value * 0.9) + 0.1 * lvl.value + 0.04 * energy.value }],
  }));
  const ring1 = useAnimatedStyle(() => ({ transform: [{ rotate: `${phase.value * 12}deg` }] }));
  const ring2 = useAnimatedStyle(() => ({ transform: [{ rotate: `${-phase.value * 20}deg` }] }));
  const ring3 = useAnimatedStyle(() => ({
    transform: [{ rotate: `${phase.value * 38}deg` }],
    opacity: 0.55 + 0.45 * energy.value,
  }));
  const particles = useAnimatedStyle(() => ({ transform: [{ rotate: `${-phase.value * 9}deg` }], opacity: 0.5 + 0.5 * energy.value }));
  const gyroA = useAnimatedStyle(() => ({ transform: [{ rotate: `${phase.value * 27}deg` }], opacity: 0.35 + 0.5 * energy.value }));
  const gyroB = useAnimatedStyle(() => ({ transform: [{ rotate: `${-phase.value * 41 + 60}deg` }], opacity: 0.25 + 0.5 * energy.value }));
  const core = useAnimatedStyle(() => ({
    transform: [
      {
        scale:
          1 +
          0.022 * Math.sin(clock.value * (1.4 + energy.value * 2.4)) +
          0.13 * lvl.value +
          0.02 * Math.sin(clock.value * 11) * lvl.value,
      },
    ],
  }));
  const coreGlow = useAnimatedStyle(() => ({ opacity: 0.45 + 0.35 * energy.value + 0.3 * lvl.value }));

  const r1 = useAnimatedStyle(() => rippleStyle(ripplePhase.value, 0, ripple.value));
  const r2 = useAnimatedStyle(() => rippleStyle(ripplePhase.value, 1 / 3, ripple.value));
  const r3 = useAnimatedStyle(() => rippleStyle(ripplePhase.value, 2 / 3, ripple.value));

  const ticks = useMemo(
    () =>
      Array.from({ length: 90 }, (_, i) => {
        const a = (i / 90) * Math.PI * 2;
        const long = i % 5 === 0;
        const r0 = S * 0.415;
        const r1v = S * (long ? 0.44 : 0.43);
        return { x1: c + r0 * Math.cos(a), y1: c + r0 * Math.sin(a), x2: c + r1v * Math.cos(a), y2: c + r1v * Math.sin(a), long };
      }),
    [S, c],
  );
  const dots = useMemo(
    () =>
      Array.from({ length: 34 }, (_, i) => {
        const a = (i / 34) * Math.PI * 2 + Math.sin(i * 7.3) * 0.12;
        const r = S * (0.335 + ((i * 37) % 10) * 0.004);
        return { x: c + r * Math.cos(a), y: c + r * Math.sin(a), s: 0.8 + ((i * 13) % 5) * 0.35, o: 0.35 + ((i * 7) % 6) * 0.1 };
      }),
    [S, c],
  );
  const coreR = S * 0.27;

  const layer = { position: 'absolute' as const, width: S, height: S };

  return (
    <View style={{ width: S, height: S }} pointerEvents="none">
      {/* Dış ışık */}
      <Animated.View style={[layer, halo]}>
        <Svg width={S} height={S}>
          <Defs>
            <RadialGradient id="halo" cx="50%" cy="50%" r="50%">
              <Stop offset="0.35" stopColor={C.cyan} stopOpacity="0.55" />
              <Stop offset="0.62" stopColor={C.blue} stopOpacity="0.16" />
              <Stop offset="1" stopColor={C.blue} stopOpacity="0" />
            </RadialGradient>
          </Defs>
          <Circle cx={c} cy={c} r={c} fill="url(#halo)" />
        </Svg>
      </Animated.View>

      {/* Yayılan halkalar (konuşma / dinleme) */}
      {[r1, r2, r3].map((st, i) => (
        <Animated.View
          key={i}
          style={[
            styles.ripple,
            { width: coreR * 2, height: coreR * 2, borderRadius: coreR, left: c - coreR, top: c - coreR },
            st,
          ]}
        />
      ))}

      {/* Dış kesikli halka + parlak yaylar */}
      <Animated.View style={[layer, ring1]}>
        <Svg width={S} height={S}>
          <Circle cx={c} cy={c} r={S * 0.475} stroke={C.blue} strokeOpacity={0.45} strokeWidth={1} strokeDasharray="2 7" fill="none" />
          {[0, 90, 180, 270].map((a) => (
            <Path key={a} d={arc(c, c, S * 0.475, a + 8, a + 52)} stroke={C.cyanSoft} strokeWidth={2.2} strokeLinecap="round" fill="none" opacity={0.85} />
          ))}
          <Path d={arc(c, c, S * 0.49, 200, 238)} stroke="#F43F5E" strokeWidth={1.6} fill="none" opacity={0.55} />
          <Path d={arc(c, c, S * 0.49, 20, 58)} stroke="#F43F5E" strokeWidth={1.6} fill="none" opacity={0.55} />
        </Svg>
      </Animated.View>

      {/* Ölçek çizgili halka */}
      <Animated.View style={[layer, ring2]}>
        <Svg width={S} height={S}>
          {ticks.map((t, i) => (
            <Line key={i} x1={t.x1} y1={t.y1} x2={t.x2} y2={t.y2} stroke={C.cyan} strokeOpacity={t.long ? 0.8 : 0.35} strokeWidth={t.long ? 1.4 : 0.8} />
          ))}
        </Svg>
      </Animated.View>

      {/* Hızlı segment halkası */}
      <Animated.View style={[layer, ring3]}>
        <Svg width={S} height={S}>
          <Path d={arc(c, c, S * 0.385, 0, 70)} stroke={C.cyan} strokeWidth={3} strokeLinecap="round" fill="none" />
          <Path d={arc(c, c, S * 0.385, 130, 160)} stroke={C.cyanSoft} strokeWidth={3} strokeLinecap="round" fill="none" />
          <Path d={arc(c, c, S * 0.385, 210, 300)} stroke={C.blue} strokeWidth={2} strokeLinecap="round" fill="none" opacity={0.8} />
          <Circle cx={c} cy={c} r={S * 0.365} stroke={C.blue} strokeOpacity={0.25} strokeWidth={1} fill="none" />
        </Svg>
      </Animated.View>

      {/* Parçacıklar */}
      <Animated.View style={[layer, particles]}>
        <Svg width={S} height={S}>
          {dots.map((d, i) => (
            <Circle key={i} cx={d.x} cy={d.y} r={d.s} fill={C.cyanSoft} opacity={d.o} />
          ))}
        </Svg>
      </Animated.View>

      {/* Çekirdek küre */}
      <Animated.View style={[layer, core]}>
        <Animated.View style={[layer, coreGlow]}>
          <Svg width={S} height={S}>
            <Defs>
              <RadialGradient id="coreGlow" cx="50%" cy="50%" r="50%">
                <Stop offset="0.45" stopColor={C.cyan} stopOpacity="0.5" />
                <Stop offset="0.6" stopColor={C.cyan} stopOpacity="0" />
              </RadialGradient>
            </Defs>
            <Circle cx={c} cy={c} r={coreR * 1.55} fill="url(#coreGlow)" />
          </Svg>
        </Animated.View>
        <Svg width={S} height={S} style={StyleSheet.absoluteFill}>
          <Defs>
            <RadialGradient id="sphere" cx="42%" cy="38%" r="62%">
              <Stop offset="0" stopColor="#A5F3FC" stopOpacity="0.95" />
              <Stop offset="0.28" stopColor="#22D3EE" stopOpacity="0.75" />
              <Stop offset="0.65" stopColor="#0E4F8A" stopOpacity="0.85" />
              <Stop offset="1" stopColor="#061A38" stopOpacity="0.95" />
            </RadialGradient>
          </Defs>
          <Circle cx={c} cy={c} r={coreR} fill="url(#sphere)" />
          <G opacity={0.4}>
            {[-0.66, -0.33, 0, 0.33, 0.66].map((k) => {
              const y = c + k * coreR;
              const half = Math.sqrt(1 - k * k) * coreR;
              return <Line key={`lat${k}`} x1={c - half} y1={y} x2={c + half} y2={y} stroke="#CFFAFE" strokeWidth={0.7} />;
            })}
            {[0.25, 0.55, 0.85].map((k) => (
              <Ellipse key={`mer${k}`} cx={c} cy={c} rx={coreR * k} ry={coreR} stroke="#CFFAFE" strokeWidth={0.7} fill="none" />
            ))}
            <Line x1={c} y1={c - coreR} x2={c} y2={c + coreR} stroke="#CFFAFE" strokeWidth={0.7} />
          </G>
          <Circle cx={c} cy={c} r={coreR} stroke={C.cyanSoft} strokeWidth={1.5} fill="none" opacity={0.9} />
        </Svg>
      </Animated.View>

      {/* Jiroskop halkaları */}
      <Animated.View style={[layer, gyroA]}>
        <Svg width={S} height={S}>
          <Ellipse cx={c} cy={c} rx={coreR * 1.12} ry={coreR * 0.38} stroke={C.cyanSoft} strokeWidth={1.2} fill="none" />
        </Svg>
      </Animated.View>
      <Animated.View style={[layer, gyroB]}>
        <Svg width={S} height={S}>
          <Ellipse cx={c} cy={c} rx={coreR * 1.18} ry={coreR * 0.3} stroke={C.blue} strokeWidth={1} fill="none" />
        </Svg>
      </Animated.View>

      <Text style={[styles.label, { top: S * 0.175 - 8, width: S, fontSize: Math.max(10, S * 0.028) }]}>J A R V I S</Text>
    </View>
  );
}

function rippleStyle(p: number, offset: number, amount: number) {
  'worklet';
  const prog = (p + offset) % 1;
  return {
    opacity: (1 - prog) * amount * 0.75,
    transform: [{ scale: 1 + prog * 1.05 }],
  };
}

const styles = StyleSheet.create({
  ripple: { position: 'absolute', borderWidth: 1.5, borderColor: C.cyanSoft },
  label: { position: 'absolute', textAlign: 'center', color: C.cyanSoft, fontSize: 12, letterSpacing: 6, fontWeight: '600', opacity: 0.9 },
});

export default memo(Orb);
