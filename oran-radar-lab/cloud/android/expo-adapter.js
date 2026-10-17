// Copy into the accessible Expo source project; never patch the signed APK binary.
// Install with: npx expo install expo-secure-store expo-notifications
import * as SecureStore from 'expo-secure-store';
import * as Notifications from 'expo-notifications';
import {Platform} from 'react-native';
import {CloudClient} from './cloud-client.mjs';

export function createCloudClient(baseUrl, cache) {
  return new CloudClient({baseUrl,cache,getToken:()=>SecureStore.getItemAsync('oran-user-token')});
}
export async function saveUserToken(token) {
  // This is a scoped, revocable user token provisioned by the administrator.
  // ADMIN/INGEST tokens and FCM service account keys NEVER belong here.
  await SecureStore.setItemAsync('oran-user-token',token);
}
export async function enableAndroidPush(client) {
  if(Platform.OS!=='android')throw new Error('Bu bağdaştırıcı Android için hazırlanmıştır');
  await Notifications.setNotificationChannelAsync('signals',{name:'Radar fırsatları',importance:Notifications.AndroidImportance.HIGH});
  const p=await Notifications.requestPermissionsAsync();
  if(p.status!=='granted')return {enabled:false,reason:'permission_denied'};
  const native=await Notifications.getDevicePushTokenAsync(); // Native FCM token, not Expo Push token.
  return {enabled:true,...await client.registerPushToken(native.data)};
}
