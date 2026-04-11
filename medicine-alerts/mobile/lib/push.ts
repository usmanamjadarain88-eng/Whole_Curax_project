import * as Notifications from 'expo-notifications';

export async function getDevicePushToken(): Promise<string> {
  try {
    const perm = await Notifications.getPermissionsAsync();
    let finalStatus = perm.status;
    if (finalStatus !== 'granted') {
      const req = await Notifications.requestPermissionsAsync();
      finalStatus = req.status;
    }
    if (finalStatus !== 'granted') return '';
    const token = await Notifications.getDevicePushTokenAsync();
    const raw = (token as { data?: string } | null)?.data || '';
    return (raw || '').trim();
  } catch {
    return '';
  }
}

