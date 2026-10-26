import { LinearGradient } from 'expo-linear-gradient';
import { ReactNode, useMemo } from 'react';
import { Pressable, StyleSheet, Text, TextInput, TextInputProps, View, ViewStyle } from 'react-native';
import Svg, { Circle, Defs, Line, Pattern, Rect } from 'react-native-svg';
import { C, font } from './theme';

export function Background() {
  const dots = useMemo(
    () => Array.from({ length: 40 }, (_, i) => ({ x: (i * 97) % 100, y: (i * 61) % 100, r: 0.6 + ((i * 7) % 4) * 0.35, o: 0.15 + ((i * 11) % 5) * 0.08 })),
    [],
  );
  return (
    <View style={StyleSheet.absoluteFill} pointerEvents="none">
      <LinearGradient colors={[C.bg2, C.bg, '#01040C']} start={{ x: 0.5, y: 0 }} end={{ x: 0.5, y: 1 }} style={StyleSheet.absoluteFill} />
      <Svg width="100%" height="100%" style={StyleSheet.absoluteFill}>
        <Defs>
          <Pattern id="grid" width="36" height="36" patternUnits="userSpaceOnUse">
            <Line x1="0" y1="0" x2="36" y2="0" stroke={C.blue} strokeOpacity="0.06" strokeWidth="1" />
            <Line x1="0" y1="0" x2="0" y2="36" stroke={C.blue} strokeOpacity="0.06" strokeWidth="1" />
          </Pattern>
        </Defs>
        <Rect width="100%" height="100%" fill="url(#grid)" />
        {dots.map((d, i) => (
          <Circle key={i} cx={`${d.x}%`} cy={`${d.y}%`} r={d.r} fill={C.cyanSoft} opacity={d.o} />
        ))}
      </Svg>
    </View>
  );
}

export function Glass({ title, right, children, style }: { title?: string; right?: ReactNode; children: ReactNode; style?: ViewStyle }) {
  return (
    <View style={[s.glass, style]}>
      <LinearGradient
        colors={['rgba(56,189,248,0.10)', 'rgba(8,28,58,0.0)']}
        start={{ x: 0, y: 0 }}
        end={{ x: 0.6, y: 0.6 }}
        style={[StyleSheet.absoluteFill, { borderRadius: 18 }]}
        pointerEvents="none"
      />
      {title ? (
        <View style={s.glassHead}>
          <Text style={font.title}>{title}</Text>
          {right}
        </View>
      ) : null}
      {children}
    </View>
  );
}

export function Btn({ label, onPress, kind = 'primary', small, disabled }: { label: string; onPress: () => void; kind?: 'primary' | 'ghost' | 'danger'; small?: boolean; disabled?: boolean }) {
  const color = kind === 'danger' ? C.red : C.cyan;
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      style={({ pressed }) => [
        s.btn,
        small && s.btnSmall,
        { borderColor: color, backgroundColor: kind === 'primary' ? 'rgba(34,211,238,0.16)' : 'transparent', opacity: disabled ? 0.4 : pressed ? 0.7 : 1 },
      ]}
    >
      <Text style={{ color, fontWeight: '700', fontSize: small ? 12 : 14, letterSpacing: 0.6 }}>{label}</Text>
    </Pressable>
  );
}

export function Chip({ label, active, onPress, color = C.cyan }: { label: string; active?: boolean; onPress?: () => void; color?: string }) {
  return (
    <Pressable onPress={onPress} style={[s.chip, { borderColor: active ? color : C.border, backgroundColor: active ? `${color}22` : 'transparent' }]}>
      <Text style={{ color: active ? color : C.sub, fontSize: 12, fontWeight: '600' }}>{label}</Text>
    </Pressable>
  );
}

export function Field(props: TextInputProps & { label?: string }) {
  return (
    <View style={{ marginBottom: 12 }}>
      {props.label ? <Text style={[font.tiny, { marginBottom: 6 }]}>{props.label.toLocaleUpperCase('tr')}</Text> : null}
      <TextInput placeholderTextColor={C.muted} {...props} style={[s.input, props.multiline && { minHeight: 90, textAlignVertical: 'top' }, props.style]} />
    </View>
  );
}

export function Check({ done, onPress, color = C.cyan, label }: { done: boolean; onPress: () => void; color?: string; label?: string }) {
  return (
    <Pressable onPress={onPress} hitSlop={10} accessibilityRole="checkbox" accessibilityState={{ checked: done }} accessibilityLabel={label} style={[s.check, { borderColor: color, backgroundColor: done ? color : 'transparent' }]}>
      {done ? <Text style={{ color: C.bg, fontSize: 12, fontWeight: '900' }}>✓</Text> : null}
    </Pressable>
  );
}

export function Dot({ color }: { color: string }) {
  return <View style={{ width: 8, height: 8, borderRadius: 4, backgroundColor: color, marginRight: 10, marginTop: 6 }} />;
}

export function Empty({ text }: { text: string }) {
  return <Text style={[font.sub, { color: C.muted, paddingVertical: 6 }]}>{text}</Text>;
}

export const s = StyleSheet.create({
  glass: {
    backgroundColor: C.panel,
    borderColor: C.border,
    borderWidth: 1,
    borderRadius: 18,
    padding: 16,
    marginBottom: 14,
    overflow: 'hidden',
    shadowColor: C.cyan,
    shadowOpacity: 0.18,
    shadowRadius: 18,
    shadowOffset: { width: 0, height: 0 },
  },
  glassHead: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 },
  btn: { borderWidth: 1, borderRadius: 12, paddingVertical: 11, paddingHorizontal: 16, alignItems: 'center', justifyContent: 'center' },
  btnSmall: { paddingVertical: 6, paddingHorizontal: 10, borderRadius: 10 },
  chip: { borderWidth: 1, borderRadius: 999, paddingVertical: 6, paddingHorizontal: 12, marginRight: 8, marginBottom: 8 },
  input: {
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 12,
    paddingHorizontal: 14,
    paddingVertical: 11,
    color: C.text,
    fontSize: 15,
    backgroundColor: 'rgba(2,10,24,0.6)',
  },
  check: { width: 22, height: 22, borderRadius: 7, borderWidth: 1.5, alignItems: 'center', justifyContent: 'center', marginRight: 12 },
  row: { flexDirection: 'row', alignItems: 'flex-start', paddingVertical: 8 },
});
