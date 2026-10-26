import { useLocalSearchParams } from 'expo-router';
import { ProjectDetail } from '../../screens/Projects';
import { Screen } from '../../ui/Screen';

export default function ProjectRoute() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return (
    <Screen>
      <ProjectDetail id={id} />
    </Screen>
  );
}
