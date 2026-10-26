import type { BottomTabBarProps } from 'expo-router/js-tabs';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useJarvis } from '../runtime/JarvisProvider';
import { C } from './theme';

// Menü bu kullanıcının işine göre: komuta merkezi, kişisel düzen, projeler, notlar.
const META: Record<string, { label: string; icon: string }> = {
  index: { label: 'Komuta', icon: '◉' },
  plan: { label: 'Planım', icon: '☰' },
  projects: { label: 'Projeler', icon: '◆' },
  notes: { label: 'Notlar', icon: '✎' },
  settings: { label: 'Ayarlar', icon: '⚙' },
};

// Telefonda altta çubuk, geniş ekranda solda dikey menü.
export function JarvisTabBar({ state, navigateToTab, insets, wide }: BottomTabBarProps & { wide: boolean }) {
  const j = useJarvis();
  const badge = (name: string) =>
    name === 'index' ? j.state.pending.length : name === 'settings' ? j.watch.alerts.filter((a) => a.key.startsWith('int:')).length : 0;
  return (
    <View style={wide ? [styles.rail, { paddingTop: insets.top + 20 }] : [styles.bar, { paddingBottom: Math.max(insets.bottom, 8) }]}>
      {state.routes.map((r, i) => {
        const m = META[r.name];
        if (!m) return null;
        const active = state.index === i;
        const b = badge(r.name);
        return (
          <Pressable
            key={r.key}
            onPress={() => navigateToTab(r.key)}
            style={[wide ? styles.railItem : styles.barItem, active && styles.active]}
            accessibilityRole="tab"
            accessibilityState={{ selected: active }}
            accessibilityLabel={m.label}
          >
            <Text style={{ color: active ? C.cyanSoft : C.muted, fontSize: wide ? 20 : 18 }}>{m.icon}</Text>
            <Text style={{ color: active ? C.cyanSoft : C.muted, fontSize: 11, marginTop: 3, letterSpacing: 0.8, fontWeight: active ? '700' : '500' }}>{m.label}</Text>
            {b ? (
              <View style={styles.badge}>
                <Text style={{ color: C.bg, fontSize: 10, fontWeight: '800' }}>{b}</Text>
              </View>
            ) : null}
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  rail: { width: 92, height: '100%', borderRightWidth: 1, borderRightColor: C.border, backgroundColor: 'rgba(2,8,20,0.6)', alignItems: 'center' },
  railItem: { width: 76, paddingVertical: 14, alignItems: 'center', borderRadius: 14, marginBottom: 6 },
  bar: { flexDirection: 'row', borderTopWidth: 1, borderTopColor: C.border, backgroundColor: 'rgba(2,8,20,0.92)', paddingTop: 8 },
  barItem: { flex: 1, alignItems: 'center', paddingVertical: 4, borderRadius: 12 },
  active: { backgroundColor: 'rgba(34,211,238,0.10)' },
  badge: { position: 'absolute', top: 2, right: 14, backgroundColor: C.amber, borderRadius: 8, minWidth: 16, height: 16, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 3 },
});
