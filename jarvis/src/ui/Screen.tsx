import { ReactNode, useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { greeting, trDate, trTime } from '../core/time';
import { useJarvis } from '../runtime/JarvisProvider';
import { Background } from './kit';
import { C } from './theme';

// Her ekranın ortak çerçevesi: opak arka plan, JARVIS başlığı, saat ve kaydırılabilir içerik.
// (Sekme ve yığın ekranları üst üste durduğu için her ekran kendi arka planını çizer.)
export function Screen({ children }: { children: ReactNode }) {
  const insets = useSafeAreaInsets();
  const { width } = useWindowDimensions();
  const wide = width >= 900;
  return (
    <View style={{ flex: 1, paddingTop: insets.top, backgroundColor: C.bg }}>
      <Background />
      <Header />
      <ScrollView
        contentContainerStyle={{ padding: 16, paddingBottom: 32, maxWidth: wide ? 1500 : 720, width: '100%', alignSelf: 'center' }}
        keyboardShouldPersistTaps="handled"
      >
        {children}
      </ScrollView>
    </View>
  );
}

function Header() {
  const j = useJarvis();
  const [now, setNow] = useState(new Date());
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 15_000);
    return () => clearInterval(t);
  }, []);
  const issues = j.watch.alerts.filter((a) => a.level === 'kritik').length;
  const name = j.state.profile.address || j.state.profile.name;
  return (
    <View style={styles.header}>
      <View style={{ flex: 1 }}>
        <Text style={styles.logo}>JARVIS</Text>
        <Text style={styles.greet} numberOfLines={2}>
          {greeting(now)}
          {name ? `, ${name}` : ''}. {!j.checked ? 'Sistemler kontrol ediliyor…' : issues ? `${issues} kritik konu var.` : 'Tüm sistemler çalışıyor.'}
        </Text>
      </View>
      <View style={{ alignItems: 'flex-end' }}>
        <Text style={styles.clock}>{trTime(now)}</Text>
        <Text style={styles.date}>{trDate(now)}</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingTop: 8, paddingBottom: 6 },
  logo: { color: C.cyanSoft, fontSize: 26, fontWeight: '300', letterSpacing: 10, textShadowColor: C.cyan, textShadowRadius: 12 },
  greet: { color: C.sub, fontSize: 12, marginTop: 2, letterSpacing: 0.4 },
  clock: { color: C.text, fontSize: 30, fontWeight: '200', letterSpacing: 1 },
  date: { color: C.sub, fontSize: 11 },
});
