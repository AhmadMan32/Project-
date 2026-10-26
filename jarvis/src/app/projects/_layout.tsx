import { Stack } from 'expo-router';
import { C } from '../../ui/theme';

// Proje listesi → proje detayı; Android geri tuşu detaydan listeye döner.
export default function ProjectsLayout() {
  return <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: C.bg }, animation: 'fade' }} />;
}
