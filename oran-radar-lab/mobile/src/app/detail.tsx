import React from 'react';
import { useLocalSearchParams } from 'expo-router';
import DetailScreen from '../components/DetailScreen';
export default function Detail() {
  const { pair, signalId, paperId } = useLocalSearchParams<{ pair: string; signalId?: string; paperId?: string }>();
  return <DetailScreen pair={pair || ''} signalId={signalId || undefined} paperId={paperId || undefined} />;
}
