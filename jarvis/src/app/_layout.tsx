import { DarkTheme, ThemeProvider } from 'expo-router';
import { Tabs } from 'expo-router/js-tabs';
import { StatusBar } from 'expo-status-bar';
import { ActivityIndicator, Text, useWindowDimensions, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { JarvisProvider, useJarvis } from '../runtime/JarvisProvider';
import { JarvisTabBar } from '../ui/TabBar';
import { C } from '../ui/theme';

const theme = { ...DarkTheme, colors: { ...DarkTheme.colors, background: C.bg, card: C.bg, primary: C.cyan, text: C.text, border: C.border } };

export default function RootLayout() {
  return (
    <SafeAreaProvider>
      <ThemeProvider value={theme}>
        <JarvisProvider>
          <View style={{ flex: 1, backgroundColor: C.bg }}>
            <StatusBar style="light" />
            <Shell />
          </View>
        </JarvisProvider>
      </ThemeProvider>
    </SafeAreaProvider>
  );
}

function Shell() {
  const { ready } = useJarvis();
  const { width } = useWindowDimensions();
  const wide = width >= 900;
  if (!ready)
    return (
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
        <ActivityIndicator color={C.cyan} />
        <Text style={{ color: C.sub, marginTop: 12, letterSpacing: 2 }}>SİSTEMLER BAŞLATILIYOR</Text>
      </View>
    );
  return (
    <Tabs
      tabBar={(p) => <JarvisTabBar {...p} wide={wide} />}
      screenOptions={{ headerShown: false, tabBarPosition: wide ? 'left' : 'bottom', sceneStyle: { backgroundColor: C.bg } }}
    >
      <Tabs.Screen name="index" options={{ title: 'Komuta' }} />
      <Tabs.Screen name="plan" options={{ title: 'Planım' }} />
      <Tabs.Screen name="projects" options={{ title: 'Projeler' }} />
      <Tabs.Screen name="notes" options={{ title: 'Notlar' }} />
      <Tabs.Screen name="settings" options={{ title: 'Ayarlar' }} />
    </Tabs>
  );
}
