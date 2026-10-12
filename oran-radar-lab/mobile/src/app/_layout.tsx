import React from 'react';
import { Stack } from 'expo-router';
import { BG } from '../../App';
import { SafeAreaProvider } from 'react-native-safe-area-context';

export default function Layout() {
  return <SafeAreaProvider><Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: BG } }} /></SafeAreaProvider>;
}
