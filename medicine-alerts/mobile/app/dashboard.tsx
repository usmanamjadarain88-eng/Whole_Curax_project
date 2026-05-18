import { useCallback, useEffect, useState, useRef } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, Alert, TextInput, ScrollView, AppState } from 'react-native';
import { useRouter, useFocusEffect } from 'expo-router';
import { getStoredUser, clearStoredUser, getLogs, updateStoredUser } from '../lib/storage';
import type { StoredUser, AlertLog } from '../lib/storage';
import { COLORS } from '../lib/constants';
import {
  getAdminData,
  notifyBackend,
  buildSyncPayloadFromAdminData,
  postAdminSync,
  putMedicalReminders,
  saveCredentials,
  connectToAdmin,
  updateAdminFcmToken,
  getAdminConnection,
} from '../lib/api';
import { getDevicePushToken } from '../lib/push';
import { LogsTable } from './components/LogsTable';
import { ReportsSummary } from './components/ReportsSummary';

export default function DashboardScreen() {
  const router = useRouter();
  const [user, setUser] = useState<StoredUser | null>(null);
  const [adminTab, setAdminTab] = useState<'Dashboard' | 'Reminders' | 'Reports' | 'Settings' | 'Logs' | 'Alerts'>('Dashboard');
  const [logs, setLogs] = useState<AlertLog[]>([]);
  const [selectedUser, setSelectedUser] = useState<{ id: string; name: string } | null>(null);
  const [userProfileTab, setUserProfileTab] = useState<'Dashboard' | 'Reminders' | 'Reports' | 'Settings'>('Dashboard');
  const [backendData, setBackendData] = useState<Record<string, unknown> | null>(null);
  const [linkCode, setLinkCode] = useState('');
  const [addMedName, setAddMedName] = useState('');
  const [addMedBox, setAddMedBox] = useState('B1');
  const [addMedTime, setAddMedTime] = useState('08:00');
  const [addReminderTitle, setAddReminderTitle] = useState('');
  const [userConnectCode, setUserConnectCode] = useState('');
  const [connectionData, setConnectionData] = useState<{
    fcm_token_set: boolean;
    connected: boolean;
    linked_users: Array<{ id: string; name?: string; bot_id?: string }>;
  } | null>(null);

  const [userProfileData, setUserProfileData] = useState<Record<string, unknown> | null>(null);

  /** Per-user dashboard data for main admin view: key = user id, value = getAdminData(..., userId) */
  const [linkedUsersDashboardData, setLinkedUsersDashboardData] = useState<Record<string, Record<string, unknown> | null>>({});
  /** Which "entity" (Admin=0, User1=1, ...) is shown in stock distribution on main dashboard */
  const [stockDistributionIndex, setStockDistributionIndex] = useState(0);
  /** Which "entity" is shown in Dashboard tab adherence chart (below stock distribution) */
  const [dashboardChartUserIndex, setDashboardChartUserIndex] = useState(0);

  const isMainDashboard = selectedUser === null;
  const logsForView = isMainDashboard ? logs : logs.filter((l) => l.userId === selectedUser?.id);

  const loadUserProfileData = useCallback(async (userId: string) => {
    if (!user?.access_code) return;
    const data = await getAdminData(user.access_code, undefined, userId);
    setUserProfileData(data ?? null);
  }, [user?.access_code]);

  const loadUser = useCallback(async () => {
    const u = await getStoredUser();
    setUser(u);
  }, []);

  const loadLogs = useCallback(async () => {
    const list = await getLogs();
    setLogs(list);
  }, []);

  const loadFromBackend = useCallback(async (accessCode: string) => {
    const data = await getAdminData(accessCode);
    setBackendData(data ?? null);
  }, []);

  useEffect(() => {
    loadUser();
  }, [loadUser]);

  useEffect(() => {
    if (user?.role === 'admin') {
      loadLogs();
    }
  }, [user?.role, loadLogs, adminTab]);

  useEffect(() => {
    if (user?.access_code) {
      loadFromBackend(user.access_code);
    } else {
      setBackendData(null);
    }
  }, [user?.access_code, loadFromBackend]);

  const loadConnectionData = useCallback(async () => {
    if (!user?.access_code || user?.role !== 'admin') return;
    const data = await getAdminConnection(user.access_code);
    setConnectionData(data ?? null);
  }, [user?.access_code, user?.role]);

  useEffect(() => {
    if (user?.role === 'admin' && user?.access_code) {
      loadConnectionData();
    } else {
      setConnectionData(null);
    }
  }, [user?.role, user?.access_code, loadConnectionData]);

  /** Load each linked user's dashboard data for main admin view (medicine inventory, stock by user) */
  const loadLinkedUsersDashboardData = useCallback(async () => {
    if (!user?.access_code || user?.role !== 'admin' || !connectionData?.linked_users?.length) {
      setLinkedUsersDashboardData({});
      return;
    }
    const next: Record<string, Record<string, unknown> | null> = {};
    for (const u of connectionData.linked_users) {
      const data = await getAdminData(user.access_code, undefined, u.id);
      next[u.id] = data ?? null;
    }
    setLinkedUsersDashboardData(next);
  }, [user?.access_code, user?.role, connectionData?.linked_users]);

  useEffect(() => {
    loadLinkedUsersDashboardData();
  }, [loadLinkedUsersDashboardData]);

  useEffect(() => {
    if (selectedUser && user?.access_code) {
      loadUserProfileData(selectedUser.id);
    } else {
      setUserProfileData(null);
    }
  }, [selectedUser?.id, user?.access_code, loadUserProfileData]);

  useEffect(() => {
    const syncAdminRegistration = async () => {
      if (!user || user.role !== 'admin' || !user.access_code) return;
      const botId = user.bot_id || `admin_${Math.random().toString(36).slice(2, 10)}`;
      const apiKey = user.api_key || `${Math.random().toString(36).slice(2, 10)}${Math.random().toString(36).slice(2, 10)}`;
      const fcmToken = (await getDevicePushToken()) || user.fcm_token || '';
      const resp = await saveCredentials({
        bot_id: botId,
        api_key: apiKey,
        role: 'admin',
        access_code: user.access_code,
        email: user.email || '',
        name: (user.email || '').split('@')[0] || 'Admin',
        fcm_token: fcmToken,
      });
      if (resp && (resp.message as string) === 'ok') {
        await updateStoredUser({
          bot_id: botId,
          api_key: apiKey,
          fcm_token: fcmToken,
          access_code: ((resp.admin_access_code as string) || user.access_code || '').trim(),
          connection_code: ((resp.connection_code as string) || user.connection_code || '').trim(),
        });
      }
      // When FCM token is obtained (now or later), ensure backend has it so desktop shows "FCM: Set" and push works
      if (fcmToken && fcmToken.trim()) {
        await updateAdminFcmToken(user.access_code, fcmToken);
      }
    };
    syncAdminRegistration();
  }, [user?.role, user?.access_code]);

  /** Refresh FCM token in backend whenever dashboard is focused or app comes to foreground.
   * So push keeps working without user having to press Connect again (token can change/expire). */
  const refreshFcmTokenInBackend = useCallback(async () => {
    if (!user?.access_code || user?.role !== 'admin') return;
    const fcmToken = (await getDevicePushToken()) || user.fcm_token || '';
    if (fcmToken?.trim()) {
      await updateAdminFcmToken(user.access_code, fcmToken);
    }
  }, [user?.access_code, user?.role, user?.fcm_token]);

  useFocusEffect(
    useCallback(() => {
      refreshFcmTokenInBackend();
    }, [refreshFcmTokenInBackend])
  );

  const appStateRef = useRef(AppState.currentState);
  useEffect(() => {
    const sub = AppState.addEventListener('change', (nextState) => {
      if (appStateRef.current.match(/inactive|background/) && nextState === 'active') {
        refreshFcmTokenInBackend();
      }
      appStateRef.current = nextState;
    });
    return () => sub.remove();
  }, [refreshFcmTokenInBackend]);

  const handleLinkDesktop = async () => {
    const code = linkCode.trim();
    if (!code) {
      Alert.alert('Link to desktop', 'Enter the access code from your desktop app.');
      return;
    }
    const botId = user?.bot_id || `admin_${Math.random().toString(36).slice(2, 10)}`;
    const apiKey = user?.api_key || `${Math.random().toString(36).slice(2, 10)}${Math.random().toString(36).slice(2, 10)}`;
    const fcmToken = (await getDevicePushToken()) || user?.fcm_token || '';
    const resp = await saveCredentials({
      bot_id: botId,
      api_key: apiKey,
      role: 'admin',
      access_code: code,
      email: user?.email || '',
      name: (user?.email || '').split('@')[0] || 'Admin',
      fcm_token: fcmToken,
    });
    if (!resp || (resp.message as string) !== 'ok') {
      Alert.alert('Link failed', 'Could not register this app with backend. Check access code.');
      return;
    }
    await updateStoredUser({
      access_code: ((resp.admin_access_code as string) || code || '').trim(),
      connection_code: ((resp.connection_code as string) || user?.connection_code || '').trim(),
      bot_id: botId,
      api_key: apiKey,
      fcm_token: fcmToken,
    });
    setLinkCode('');
    await loadUser();
    await notifyBackend(code);
    await loadFromBackend(code);
    Alert.alert('Linked', 'This app is now synced with your desktop. Changes here will appear on desktop.');
  };

  const saveToDesktop = async () => {
    if (!user?.access_code || !backendData) return;
    const payload = buildSyncPayloadFromAdminData(backendData);
    const ok = await postAdminSync(user.access_code, payload);
    if (ok) {
      await notifyBackend(user.access_code);
      await loadFromBackend(user.access_code);
      Alert.alert('Saved', 'Changes sent to desktop. Desktop will show them when it refreshes.');
    } else {
      Alert.alert('Error', 'Could not save to backend. Check connection.');
    }
  };

  const addMedicine = async (name: string, boxId: string, time: string) => {
    if (!user?.access_code || !backendData) return;
    const payload = buildSyncPayloadFromAdminData(backendData);
    payload.medicine_boxes[boxId] = {
      name: name || 'Medicine',
      quantity: 0,
      times: [time || '08:00'],
      dose_per_day: 1,
      exact_time: time || '08:00',
      instructions: '',
    };
    const ok = await postAdminSync(user.access_code, payload);
    if (ok) {
      await notifyBackend(user.access_code);
      await loadFromBackend(user.access_code);
    }
    return ok;
  };

  const addReminder = async (title: string) => {
    if (!user?.access_code) return false;
    const reminders = (backendData?.medical_reminders as Record<string, unknown[]>) || {
      appointments: [],
      prescriptions: [],
      lab_tests: [],
      custom: [],
    };
    const custom = [...(reminders.custom || []), { id: Date.now().toString(), title, type: 'custom' }];
    const ok = await putMedicalReminders(user.access_code, {
      ...reminders,
      custom,
    });
    if (ok) {
      await notifyBackend(user.access_code);
      await loadFromBackend(user.access_code);
    }
    return ok;
  };

  const saveToDesktopForUser = async () => {
    if (!user?.access_code || !selectedUser || !userProfileData) return;
    const payload = buildSyncPayloadFromAdminData(userProfileData);
    const ok = await postAdminSync(user.access_code, payload, selectedUser.id);
    if (ok) {
      await notifyBackend(user.access_code);
      await loadUserProfileData(selectedUser.id);
      Alert.alert('Saved', `Changes saved for ${selectedUser.name}.`);
    } else {
      Alert.alert('Error', 'Could not save. Check connection.');
    }
  };

  const addMedicineForUser = async (name: string, boxId: string, time: string) => {
    if (!user?.access_code || !selectedUser || !userProfileData) return false;
    const payload = buildSyncPayloadFromAdminData(userProfileData);
    payload.medicine_boxes[boxId] = {
      name: name || 'Medicine',
      quantity: 0,
      times: [time || '08:00'],
      dose_per_day: 1,
      exact_time: time || '08:00',
      instructions: '',
    };
    const ok = await postAdminSync(user.access_code, payload, selectedUser.id);
    if (ok) await loadUserProfileData(selectedUser.id);
    return ok;
  };

  const addReminderForUser = async (title: string) => {
    if (!user?.access_code || !selectedUser || !userProfileData) return false;
    const reminders = (userProfileData?.medical_reminders as Record<string, unknown[]>) || {
      appointments: [],
      prescriptions: [],
      lab_tests: [],
      custom: [],
    };
    const custom = [...(reminders.custom || []), { id: Date.now().toString(), title, type: 'custom' }];
    const ok = await putMedicalReminders(user.access_code, { ...reminders, custom }, selectedUser.id);
    if (ok) await loadUserProfileData(selectedUser.id);
    return ok;
  };

  const handleLogout = () => {
    Alert.alert('Logout', 'Clear local data and return to sign up?', [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Logout',
        style: 'destructive',
        onPress: async () => {
          await clearStoredUser();
          router.replace('/signup');
        },
      },
    ]);
  };

  const handleUserConnect = async () => {
    if (!user) return;
    const code = userConnectCode.trim();
    if (!code) {
      Alert.alert('Connect to admin', 'Enter admin connection code.');
      return;
    }
    const botId = user.bot_id || `user_${Math.random().toString(36).slice(2, 10)}`;
    const apiKey = user.api_key || `${Math.random().toString(36).slice(2, 10)}${Math.random().toString(36).slice(2, 10)}`;
    const fcmToken = (await getDevicePushToken()) || user.fcm_token || '';
    const resp = await connectToAdmin({
      connection_code: code,
      bot_id: botId,
      api_key: apiKey,
      name: (user.email || '').split('@')[0] || 'User',
      fcm_token: fcmToken,
    });
    if (!resp || (resp.message as string) !== 'ok') {
      Alert.alert('Connect failed', 'Invalid connection code or backend unavailable.');
      return;
    }
    await updateStoredUser({
      connection_code: code,
      bot_id: botId,
      api_key: apiKey,
      fcm_token: fcmToken,
    });
    setUserConnectCode('');
    await loadUser();
    Alert.alert('Connected', 'User linked to admin. Background push registration updated.');
  };

  if (!user) {
    return (
      <View style={styles.centered}>
        <Text style={styles.loading}>Loading...</Text>
      </View>
    );
  }

  const isAdmin = user.role === 'admin';

  if (isAdmin && selectedUser) {
    const dataForUser = userProfileData;
    const medicines = (dataForUser?.medicines as Record<string, unknown>[]) || [];
    const reminders = (dataForUser?.medical_reminders as Record<string, unknown[]>) || { custom: [] };
    const customReminders = reminders.custom || [];
    const boxes = (dataForUser?.medicine_boxes as Record<string, Record<string, unknown>>) || {};

    return (
      <View style={[styles.container, styles.containerAdmin]}>
        <View style={styles.header}>
          <TouchableOpacity onPress={() => setSelectedUser(null)} style={styles.backButton} activeOpacity={0.8}>
            <Text style={styles.backButtonText}>← Back to dashboard</Text>
          </TouchableOpacity>
          <Text style={styles.roleBadge}>{selectedUser.name}</Text>
        </View>
        <View style={styles.tabBar}>
          <TouchableOpacity style={[styles.tab, userProfileTab === 'Dashboard' && styles.tabActive]} onPress={() => setUserProfileTab('Dashboard')}>
            <Text style={[styles.tabText, userProfileTab === 'Dashboard' && styles.tabTextActive]}>Dashboard</Text>
          </TouchableOpacity>
          <TouchableOpacity style={[styles.tab, userProfileTab === 'Reminders' && styles.tabActive]} onPress={() => setUserProfileTab('Reminders')}>
            <Text style={[styles.tabText, userProfileTab === 'Reminders' && styles.tabTextActive]}>Reminders</Text>
          </TouchableOpacity>
          <TouchableOpacity style={[styles.tab, userProfileTab === 'Reports' && styles.tabActive]} onPress={() => setUserProfileTab('Reports')}>
            <Text style={[styles.tabText, userProfileTab === 'Reports' && styles.tabTextActive]}>Reports</Text>
          </TouchableOpacity>
          <TouchableOpacity style={[styles.tab, userProfileTab === 'Settings' && styles.tabActive]} onPress={() => setUserProfileTab('Settings')}>
            <Text style={[styles.tabText, userProfileTab === 'Settings' && styles.tabTextActive]}>Settings</Text>
          </TouchableOpacity>
        </View>

        {userProfileTab === 'Dashboard' && (
          <View style={styles.logsSection}>
            <Text style={styles.logsTitle}>Dashboard — {selectedUser.name}</Text>
            <Text style={styles.logsHint}>6 boxes, stock, medicine inventory. Same as admin dashboard.</Text>
            <View style={styles.card}>
              <Text style={styles.editSectionTitle}>6 boxes / stock</Text>
              {['B1', 'B2', 'B3', 'B4', 'B5', 'B6'].map((boxId) => {
                const box = boxes[boxId];
                const med = medicines.find((m: Record<string, unknown>) => (m.box_id as string) === boxId);
                const name = (box?.name as string) || (med?.name as string) || '—';
                const qty = box?.quantity ?? med?.quantity ?? 0;
                return <Text key={boxId} style={styles.editRow}>{boxId}: {String(name)} — {String(qty)} left</Text>;
              })}
              <Text style={styles.editSectionTitle}>Medicine inventory — add</Text>
              {medicines.length > 0 ? medicines.map((m: Record<string, unknown>, i: number) => (
                <Text key={i} style={styles.editRow}>{(m.name as string) || 'Medicine'} ({(m.box_id as string) || '?'})</Text>
              )) : <Text style={styles.hint}>No medicines yet.</Text>}
              <View style={styles.linkSection}>
                <TextInput style={styles.linkInput} placeholder="Medicine name" placeholderTextColor={COLORS.textSecondary} value={addMedName} onChangeText={setAddMedName} />
                <View style={styles.row}>
                  <Text style={styles.editLabel}>Box: </Text>
                  {['B1', 'B2', 'B3', 'B4', 'B5', 'B6'].map((b) => (
                    <TouchableOpacity key={b} onPress={() => setAddMedBox(b)} style={[styles.boxBtn, addMedBox === b && styles.boxBtnActive]}>
                      <Text style={[styles.boxBtnText, addMedBox === b && styles.boxBtnTextActive]}>{b}</Text>
                    </TouchableOpacity>
                  ))}
                </View>
                <TextInput style={styles.linkInput} placeholder="Time (e.g. 08:00)" placeholderTextColor={COLORS.textSecondary} value={addMedTime} onChangeText={setAddMedTime} />
                <TouchableOpacity style={styles.linkButton} onPress={async () => { const ok = await addMedicineForUser(addMedName, addMedBox, addMedTime); if (ok) setAddMedName(''); }} activeOpacity={0.8}>
                  <Text style={styles.linkButtonText}>Add medicine</Text>
                </TouchableOpacity>
              </View>
              <TouchableOpacity style={[styles.linkButton, { marginTop: 12 }]} onPress={saveToDesktopForUser} activeOpacity={0.8}>
                <Text style={styles.linkButtonText}>Save all to desktop</Text>
              </TouchableOpacity>
            </View>
            <TouchableOpacity style={styles.logoutButton} onPress={() => setSelectedUser(null)} activeOpacity={0.8}>
              <Text style={styles.logoutText}>← Back to dashboard</Text>
            </TouchableOpacity>
          </View>
        )}

        {userProfileTab === 'Reminders' && (
          <View style={styles.logsSection}>
            <Text style={styles.logsTitle}>Reminders — {selectedUser.name}</Text>
            <Text style={styles.logsHint}>Same as admin dashboard.</Text>
            <View style={styles.card}>
              <Text style={styles.editSectionTitle}>Reminders</Text>
              {customReminders.length > 0 ? customReminders.map((r: unknown, i: number) => (
                <Text key={i} style={styles.editRow}>{(r as Record<string, string>).title || 'Reminder'}</Text>
              )) : <Text style={styles.hint}>No custom reminders.</Text>}
              <View style={styles.linkSection}>
                <TextInput style={styles.linkInput} placeholder="Reminder title" placeholderTextColor={COLORS.textSecondary} value={addReminderTitle} onChangeText={setAddReminderTitle} />
                <TouchableOpacity style={styles.linkButton} onPress={async () => { if (addReminderTitle.trim()) { await addReminderForUser(addReminderTitle.trim()); setAddReminderTitle(''); } }} activeOpacity={0.8}>
                  <Text style={styles.linkButtonText}>Add reminder</Text>
                </TouchableOpacity>
              </View>
              <TouchableOpacity style={[styles.linkButton, { marginTop: 12 }]} onPress={saveToDesktopForUser} activeOpacity={0.8}>
                <Text style={styles.linkButtonText}>Save to desktop</Text>
              </TouchableOpacity>
            </View>
            <TouchableOpacity style={styles.logoutButton} onPress={() => setSelectedUser(null)} activeOpacity={0.8}>
              <Text style={styles.logoutText}>← Back to dashboard</Text>
            </TouchableOpacity>
          </View>
        )}

        {userProfileTab === 'Reports' && (
          <View style={styles.logsSection}>
            <Text style={styles.logsTitle}>Reports — {selectedUser.name}</Text>
            <Text style={styles.logsHint}>Summary and adherence from alert logs.</Text>
            <ReportsSummary logs={logsForView} showUserColumn={false} />
            <TouchableOpacity style={styles.logoutButton} onPress={() => setSelectedUser(null)} activeOpacity={0.8}>
              <Text style={styles.logoutText}>← Back to dashboard</Text>
            </TouchableOpacity>
          </View>
        )}

        {userProfileTab === 'Settings' && (
          <View style={styles.logsSection}>
            <Text style={styles.logsTitle}>Settings — {selectedUser.name}</Text>
            <View style={styles.card}>
              <Text style={styles.hint}>Same settings structure. Edits apply to this user only.</Text>
              {dataForUser && (
                <Text style={styles.syncHint}>Medicines: {medicines.length} · Reminders: {customReminders.length}</Text>
              )}
            </View>
            <TouchableOpacity style={styles.logoutButton} onPress={() => setSelectedUser(null)} activeOpacity={0.8}>
              <Text style={styles.logoutText}>← Back to dashboard</Text>
            </TouchableOpacity>
          </View>
        )}
      </View>
    );
  }

  return (
    <View style={[styles.container, isAdmin && styles.containerAdmin]}>
      <View style={styles.header}>
        <Text style={styles.logo}>CuraX</Text>
        <Text style={styles.roleBadge}>{isAdmin ? 'Admin' : 'User'}</Text>
      </View>

      {isAdmin && isMainDashboard && (
        <View style={styles.tabBar}>
          <TouchableOpacity style={[styles.tab, adminTab === 'Dashboard' && styles.tabActive]} onPress={() => setAdminTab('Dashboard')}>
            <Text style={[styles.tabText, adminTab === 'Dashboard' && styles.tabTextActive]}>Dashboard</Text>
          </TouchableOpacity>
          <TouchableOpacity style={[styles.tab, adminTab === 'Reminders' && styles.tabActive]} onPress={() => setAdminTab('Reminders')}>
            <Text style={[styles.tabText, adminTab === 'Reminders' && styles.tabTextActive]}>Reminders</Text>
          </TouchableOpacity>
          <TouchableOpacity style={[styles.tab, adminTab === 'Reports' && styles.tabActive]} onPress={() => setAdminTab('Reports')}>
            <Text style={[styles.tabText, adminTab === 'Reports' && styles.tabTextActive]}>Reports</Text>
          </TouchableOpacity>
          <TouchableOpacity style={[styles.tab, adminTab === 'Settings' && styles.tabActive]} onPress={() => setAdminTab('Settings')}>
            <Text style={[styles.tabText, adminTab === 'Settings' && styles.tabTextActive]}>Settings</Text>
          </TouchableOpacity>
          <TouchableOpacity style={[styles.tab, adminTab === 'Logs' && styles.tabActive]} onPress={() => setAdminTab('Logs')}>
            <Text style={[styles.tabText, adminTab === 'Logs' && styles.tabTextActive]}>Logs</Text>
          </TouchableOpacity>
          <TouchableOpacity style={[styles.tab, adminTab === 'Alerts' && styles.tabActive]} onPress={() => setAdminTab('Alerts')}>
            <Text style={[styles.tabText, adminTab === 'Alerts' && styles.tabTextActive]}>Alerts</Text>
          </TouchableOpacity>
        </View>
      )}

      {isAdmin && adminTab === 'Logs' ? (
        <View style={styles.logsSection}>
          <Text style={styles.logsTitle}>Alert logs</Text>
          <Text style={styles.logsHint}>Each received alert appears as one row. User column shows which user.</Text>
          <LogsTable logs={logs} onRefresh={loadLogs} showUserColumn />
          <TouchableOpacity style={styles.logoutButton} onPress={handleLogout} activeOpacity={0.8}>
            <Text style={styles.logoutText}>Logout</Text>
          </TouchableOpacity>
        </View>
      ) : isAdmin && adminTab === 'Alerts' ? (
        <View style={styles.logsSection}>
          <Text style={styles.logsTitle}>Alerts</Text>
          <Text style={styles.logsHint}>Recent alerts from all linked users. Shown only on main dashboard.</Text>
          <LogsTable logs={logs} onRefresh={loadLogs} showUserColumn />
          <TouchableOpacity style={styles.logoutButton} onPress={handleLogout} activeOpacity={0.8}>
            <Text style={styles.logoutText}>Logout</Text>
          </TouchableOpacity>
        </View>
      ) : isAdmin && adminTab === 'Reports' ? (
        <View style={styles.logsSection}>
          <Text style={styles.logsTitle}>Reports</Text>
          <Text style={styles.logsHint}>Summary and adherence from alert logs. User column shows which user.</Text>
          <ReportsSummary logs={logs} showUserColumn />
          <TouchableOpacity style={styles.logoutButton} onPress={handleLogout} activeOpacity={0.8}>
            <Text style={styles.logoutText}>Logout</Text>
          </TouchableOpacity>
        </View>
      ) : isAdmin && adminTab === 'Reminders' && user.access_code ? (
        <View style={styles.logsSection}>
          <Text style={styles.logsTitle}>Reminders</Text>
          <Text style={styles.logsHint}>Add or edit reminders. Desktop will show changes when it refreshes.</Text>
          <View style={styles.card}>
            <Text style={styles.editSectionTitle}>Reminders</Text>
            {((backendData?.medical_reminders as Record<string, unknown[]>)?.custom?.length ?? 0) > 0 ? (
              ((backendData!.medical_reminders as Record<string, unknown[]>).custom || []).map((r: unknown, i: number) => (
                <Text key={i} style={styles.editRow}>{(r as Record<string, string>).title || 'Reminder'}</Text>
              ))
            ) : (
              <Text style={styles.hint}>No custom reminders.</Text>
            )}
            <View style={styles.linkSection}>
              <TextInput style={styles.linkInput} placeholder="Reminder title" placeholderTextColor={COLORS.textSecondary} value={addReminderTitle} onChangeText={setAddReminderTitle} />
              <TouchableOpacity style={styles.linkButton} onPress={async () => { if (addReminderTitle.trim()) { await addReminder(addReminderTitle.trim()); setAddReminderTitle(''); } }} activeOpacity={0.8}>
                <Text style={styles.linkButtonText}>Add reminder</Text>
              </TouchableOpacity>
            </View>
            <TouchableOpacity style={[styles.linkButton, { marginTop: 12 }]} onPress={saveToDesktop} activeOpacity={0.8}>
              <Text style={styles.linkButtonText}>Save to desktop</Text>
            </TouchableOpacity>
          </View>
          <TouchableOpacity style={styles.logoutButton} onPress={handleLogout} activeOpacity={0.8}>
            <Text style={styles.logoutText}>Logout</Text>
          </TouchableOpacity>
        </View>
      ) : isAdmin && adminTab === 'Settings' ? (
        <View style={styles.logsSection}>
          <Text style={styles.logsTitle}>Settings</Text>
          <View style={styles.card}>
            <Text style={styles.welcome}>{isAdmin ? 'Admin Dashboard' : 'User Dashboard'}</Text>
            <Text style={styles.email}>{user.email}</Text>
            <Text style={styles.hint}>
              {user.access_code ? 'Synced with desktop. Changes you make here are saved to the same backend and will appear on desktop.' : 'Link to desktop so changes are detected on both. Enter the access code from your desktop app below.'}
            </Text>
            {isAdmin && !user.access_code && (
              <View style={styles.linkSection}>
                <TextInput style={styles.linkInput} placeholder="Desktop access code" placeholderTextColor={COLORS.textSecondary} value={linkCode} onChangeText={setLinkCode} autoCapitalize="characters" autoCorrect={false} />
                <TouchableOpacity style={styles.linkButton} onPress={handleLinkDesktop} activeOpacity={0.8}>
                  <Text style={styles.linkButtonText}>Link to desktop</Text>
                </TouchableOpacity>
              </View>
            )}
            {isAdmin && user.access_code && (
              <View style={styles.connectionPanel}>
                <Text style={styles.connectionPanelTitle}>Connection panel</Text>
                <Text style={styles.connectionRow}>FCM Token: {connectionData?.fcm_token_set ? 'Set' : 'Not set'}</Text>
                <TouchableOpacity style={styles.linkButton} onPress={async () => { const botId = user.bot_id || `admin_${Math.random().toString(36).slice(2, 10)}`; const apiKey = user.api_key || `${Math.random().toString(36).slice(2, 10)}${Math.random().toString(36).slice(2, 10)}`; const fcmToken = (await getDevicePushToken()) || user.fcm_token || ''; await saveCredentials({ bot_id: botId, api_key: apiKey, role: 'admin', access_code: user.access_code, email: user.email || '', name: (user.email || '').split('@')[0] || 'Admin', fcm_token: fcmToken }); if (fcmToken?.trim()) await updateAdminFcmToken(user.access_code, fcmToken); await loadUser(); await loadConnectionData(); Alert.alert('Connect', 'Registration sent. FCM and status updated.'); }} activeOpacity={0.8}>
                  <Text style={styles.linkButtonText}>Connect</Text>
                </TouchableOpacity>
                <Text style={styles.connectionRow}>Connected users — tap to view user:</Text>
                {connectionData?.linked_users?.length ? connectionData.linked_users.map((u) => (
                  <TouchableOpacity key={u.id} style={styles.userChip} onPress={() => setSelectedUser({ id: u.id, name: u.name || 'User' })} activeOpacity={0.8}>
                    <Text style={styles.userChipText}>{u.name || 'User'}</Text>
                    <Text style={styles.userChipHint}>Tap to view same 4 tabs</Text>
                  </TouchableOpacity>
                )) : <Text style={styles.connectionRow}>None</Text>}
              </View>
            )}
            {!isAdmin && (
              <View style={styles.linkSection}>
                <TextInput style={styles.linkInput} placeholder="Admin connection code" placeholderTextColor={COLORS.textSecondary} value={userConnectCode} onChangeText={setUserConnectCode} autoCapitalize="characters" autoCorrect={false} />
                <TouchableOpacity style={styles.linkButton} onPress={handleUserConnect} activeOpacity={0.8}>
                  <Text style={styles.linkButtonText}>Connect to admin</Text>
                </TouchableOpacity>
              </View>
            )}
            {user.access_code && backendData != null && (
              <Text style={styles.syncHint}>Backend synced. Medicines: {Object.keys((backendData.medicine_boxes as Record<string, unknown>) || {}).length}</Text>
            )}
          </View>
          <TouchableOpacity style={styles.logoutButton} onPress={handleLogout} activeOpacity={0.8}>
            <Text style={styles.logoutText}>Logout</Text>
          </TouchableOpacity>
        </View>
      ) : isAdmin && adminTab === 'Dashboard' && user.access_code ? (
        (() => {
          const linkedUsers = connectionData?.linked_users ?? [];
          const entities = [
            { id: null as string | null, name: 'Admin', data: backendData },
            ...linkedUsers.map((u) => ({ id: u.id, name: u.name || 'User', data: linkedUsersDashboardData[u.id] ?? null })),
          ];
          const safeStockIndex = Math.min(stockDistributionIndex, Math.max(0, entities.length - 1));
          const stockEntity = entities[safeStockIndex];
          const hasMultipleEntities = entities.length > 1;
          const boxes = (stockEntity?.data?.medicine_boxes as Record<string, Record<string, unknown>>) || {};
          const medicinesForStock = (stockEntity?.data?.medicines as Record<string, unknown>[]) || [];
          return (
        <View style={styles.logsSection}>
          <Text style={styles.logsTitle}>Dashboard</Text>
          <Text style={styles.logsHint}>All users' medicines below. Stock distribution: cycle with Next/Prev.</Text>
          <ScrollView style={styles.dashboardScroll} showsVerticalScrollIndicator>
          <View style={styles.card}>
            {/* Stock distribution: one user at a time, Next/Prev only when there are other users */}
            <Text style={styles.editSectionTitle}>Stock distribution — {stockEntity?.name ?? 'Admin'}</Text>
            {hasMultipleEntities && (
              <View style={styles.nextPrevRow}>
                <TouchableOpacity
                  style={[styles.nextPrevBtn, safeStockIndex <= 0 && styles.nextPrevBtnDisabled]}
                  onPress={() => setStockDistributionIndex((i) => Math.max(0, i - 1))}
                  disabled={safeStockIndex <= 0}
                  activeOpacity={0.8}
                >
                  <Text style={styles.nextPrevBtnText}>← Previous</Text>
                </TouchableOpacity>
                <Text style={styles.nextPrevLabel}>{safeStockIndex + 1} / {entities.length}</Text>
                <TouchableOpacity
                  style={[styles.nextPrevBtn, safeStockIndex >= entities.length - 1 && styles.nextPrevBtnDisabled]}
                  onPress={() => setStockDistributionIndex((i) => Math.min(entities.length - 1, i + 1))}
                  disabled={safeStockIndex >= entities.length - 1}
                  activeOpacity={0.8}
                >
                  <Text style={styles.nextPrevBtnText}>Next →</Text>
                </TouchableOpacity>
              </View>
            )}
            <Text style={styles.editSectionTitle}>6 boxes / stock</Text>
            {['B1', 'B2', 'B3', 'B4', 'B5', 'B6'].map((boxId) => {
              const med = medicinesForStock.find((m: Record<string, unknown>) => (m.box_id as string) === boxId);
              const box = boxes[boxId];
              const name = (box?.name as string) || (med?.name as string) || '—';
              const qty = box?.quantity ?? med?.quantity ?? 0;
              return <Text key={boxId} style={styles.editRow}>{boxId}: {String(name)} — {String(qty)} left</Text>;
            })}
          </View>
          {/* Adherence chart: below stock distribution; admin by default, Next/Prev when connected users exist */}
          <View style={styles.card}>
            <Text style={styles.editSectionTitle}>Adherence chart — {entities[Math.min(dashboardChartUserIndex, Math.max(0, entities.length - 1))]?.name ?? 'Admin'}</Text>
            {hasMultipleEntities && (
              <View style={styles.nextPrevRow}>
                <TouchableOpacity
                  style={[styles.nextPrevBtn, dashboardChartUserIndex <= 0 && styles.nextPrevBtnDisabled]}
                  onPress={() => setDashboardChartUserIndex((i) => Math.max(0, i - 1))}
                  disabled={dashboardChartUserIndex <= 0}
                  activeOpacity={0.8}
                >
                  <Text style={styles.nextPrevBtnText}>← Previous</Text>
                </TouchableOpacity>
                <Text style={styles.nextPrevLabel}>{Math.min(dashboardChartUserIndex, entities.length - 1) + 1} / {entities.length}</Text>
                <TouchableOpacity
                  style={[styles.nextPrevBtn, dashboardChartUserIndex >= entities.length - 1 && styles.nextPrevBtnDisabled]}
                  onPress={() => setDashboardChartUserIndex((i) => Math.min(entities.length - 1, i + 1))}
                  disabled={dashboardChartUserIndex >= entities.length - 1}
                  activeOpacity={0.8}
                >
                  <Text style={styles.nextPrevBtnText}>Next →</Text>
                </TouchableOpacity>
              </View>
            )}
            {(() => {
              const safeChartIdx = Math.min(dashboardChartUserIndex, Math.max(0, entities.length - 1));
              const chartEnt = entities[safeChartIdx];
              const logsForDashboardChart = chartEnt?.id == null ? logs.filter((l) => !l.userId) : logs.filter((l) => l.userId === chartEnt?.id);
              const total = logsForDashboardChart.length;
              const taken = logsForDashboardChart.filter((l) => l.status === 'Taken').length;
              const pct = total > 0 ? Math.round((taken / total) * 100) : 0;
              return (
                <View style={styles.adherenceChartBlock}>
                  <Text style={styles.adherenceChartValue}>{pct}%</Text>
                  <Text style={styles.adherenceChartLabel}>Adherence (Taken / Total)</Text>
                  <Text style={styles.hint}>{taken} taken, {total} total alerts</Text>
                </View>
              );
            })()}
          </View>
          <View style={styles.card}>
            <Text style={styles.editSectionTitle}>Medicine inventory (all users)</Text>
            {/* Admin section */}
            <Text style={styles.inventorySectionLabel}>— Admin —</Text>
            {(backendData?.medicines as Record<string, unknown>[])?.length > 0 ? (backendData!.medicines as Record<string, unknown>[]).map((m, i) => (
              <Text key={`admin-${i}`} style={styles.editRow}>{(m.name as string) || 'Medicine'} ({(m.box_id as string) || '?'})</Text>
            )) : <Text style={styles.hint}>No medicines yet.</Text>}
            {/* Linked users' sections */}
            {linkedUsers.map((u) => {
              const userMedicines = (linkedUsersDashboardData[u.id]?.medicines as Record<string, unknown>[]) ?? [];
              return (
                <View key={u.id} style={styles.inventoryUserBlock}>
                  <Text style={styles.inventorySectionLabel}>— User: {u.name || 'User'} —</Text>
                  {userMedicines.length > 0 ? userMedicines.map((m: Record<string, unknown>, i: number) => (
                    <Text key={`${u.id}-${i}`} style={styles.editRow}>{(m.name as string) || 'Medicine'} ({(m.box_id as string) || '?'})</Text>
                  )) : <Text style={styles.hint}>No medicines yet.</Text>}
                </View>
              );
            })}
            <Text style={styles.editSectionTitle}>Add medicine (Admin only)</Text>
            <View style={styles.linkSection}>
              <TextInput style={styles.linkInput} placeholder="Medicine name" placeholderTextColor={COLORS.textSecondary} value={addMedName} onChangeText={setAddMedName} />
              <View style={styles.row}>
                <Text style={styles.editLabel}>Box: </Text>
                {['B1', 'B2', 'B3', 'B4', 'B5', 'B6'].map((b) => (
                  <TouchableOpacity key={b} onPress={() => setAddMedBox(b)} style={[styles.boxBtn, addMedBox === b && styles.boxBtnActive]}>
                    <Text style={[styles.boxBtnText, addMedBox === b && styles.boxBtnTextActive]}>{b}</Text>
                  </TouchableOpacity>
                ))}
              </View>
              <TextInput style={styles.linkInput} placeholder="Time (e.g. 08:00)" placeholderTextColor={COLORS.textSecondary} value={addMedTime} onChangeText={setAddMedTime} />
              <TouchableOpacity style={styles.linkButton} onPress={async () => { const ok = await addMedicine(addMedName, addMedBox, addMedTime); if (ok) setAddMedName(''); }} activeOpacity={0.8}>
                <Text style={styles.linkButtonText}>Add medicine</Text>
              </TouchableOpacity>
            </View>
            <TouchableOpacity style={[styles.linkButton, { marginTop: 12 }]} onPress={saveToDesktop} activeOpacity={0.8}>
              <Text style={styles.linkButtonText}>Save all to desktop</Text>
            </TouchableOpacity>
          </View>
          </ScrollView>
          <TouchableOpacity style={styles.logoutButton} onPress={handleLogout} activeOpacity={0.8}>
            <Text style={styles.logoutText}>Logout</Text>
          </TouchableOpacity>
        </View>
          );
        })()
      ) : (
        <>
          <View style={styles.card}>
            <Text style={styles.welcome}>{isAdmin ? 'Admin Dashboard' : 'User Dashboard'}</Text>
            <Text style={styles.email}>{user.email}</Text>
            <Text style={styles.hint}>
              {user.access_code
                ? 'Synced with desktop. Go to Dashboard / Reminders / Reports / Settings tabs.'
                : isAdmin
                  ? 'Link to desktop: open Settings tab and enter the access code from your desktop app.'
                  : 'View your medicine reminders. Ask your admin for a connection code to sync with desktop.'}
            </Text>
            {isAdmin && !user.access_code && (
              <View style={styles.linkSection}>
                <TextInput style={styles.linkInput} placeholder="Desktop access code" placeholderTextColor={COLORS.textSecondary} value={linkCode} onChangeText={setLinkCode} autoCapitalize="characters" autoCorrect={false} />
                <TouchableOpacity style={styles.linkButton} onPress={handleLinkDesktop} activeOpacity={0.8}>
                  <Text style={styles.linkButtonText}>Link to desktop</Text>
                </TouchableOpacity>
              </View>
            )}
            {!isAdmin && (
              <View style={styles.linkSection}>
                <TextInput style={styles.linkInput} placeholder="Admin connection code" placeholderTextColor={COLORS.textSecondary} value={userConnectCode} onChangeText={setUserConnectCode} autoCapitalize="characters" autoCorrect={false} />
                <TouchableOpacity style={styles.linkButton} onPress={handleUserConnect} activeOpacity={0.8}>
                  <Text style={styles.linkButtonText}>Connect to admin</Text>
                </TouchableOpacity>
              </View>
            )}
          </View>
          <TouchableOpacity style={styles.logoutButton} onPress={handleLogout} activeOpacity={0.8}>
            <Text style={styles.logoutText}>Logout</Text>
          </TouchableOpacity>
        </>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: COLORS.background,
    paddingHorizontal: 24,
    paddingTop: 56,
  },
  containerAdmin: {
    backgroundColor: COLORS.adminBg,
  },
  centered: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: COLORS.background,
  },
  loading: {
    fontSize: 16,
    color: COLORS.textSecondary,
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 32,
  },
  logo: {
    fontSize: 28,
    fontWeight: '700',
    color: COLORS.primary,
  },
  roleBadge: {
    fontSize: 12,
    fontWeight: '600',
    color: COLORS.textSecondary,
    backgroundColor: COLORS.border,
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 8,
  },
  backButton: {
    paddingVertical: 8,
    paddingRight: 12,
  },
  backButtonText: {
    fontSize: 16,
    fontWeight: '600',
    color: COLORS.adminAccent,
  },
  userChip: {
    marginTop: 8,
    paddingVertical: 12,
    paddingHorizontal: 14,
    borderRadius: 10,
    backgroundColor: COLORS.border,
    borderWidth: 1,
    borderColor: COLORS.adminAccent,
  },
  userChipText: {
    fontSize: 15,
    fontWeight: '600',
    color: COLORS.text,
  },
  userChipHint: {
    fontSize: 12,
    color: COLORS.textSecondary,
    marginTop: 2,
  },
  card: {
    backgroundColor: COLORS.card,
    borderRadius: 12,
    padding: 20,
    borderWidth: 1,
    borderColor: COLORS.border,
  },
  welcome: {
    fontSize: 20,
    fontWeight: '700',
    color: COLORS.text,
    marginBottom: 6,
  },
  email: {
    fontSize: 14,
    color: COLORS.textSecondary,
    marginBottom: 12,
  },
  hint: {
    fontSize: 14,
    color: COLORS.textSecondary,
    lineHeight: 20,
  },
  linkSection: {
    marginTop: 16,
  },
  linkInput: {
    height: 44,
    borderWidth: 1,
    borderColor: COLORS.border,
    borderRadius: 8,
    paddingHorizontal: 12,
    fontSize: 15,
    backgroundColor: COLORS.background,
    marginBottom: 10,
    color: COLORS.text,
  },
  linkButton: {
    height: 44,
    borderRadius: 8,
    backgroundColor: COLORS.adminAccent,
    justifyContent: 'center',
    alignItems: 'center',
  },
  linkButtonText: {
    fontSize: 15,
    fontWeight: '600',
    color: '#fff',
  },
  syncHint: {
    fontSize: 13,
    color: COLORS.success,
    marginTop: 10,
  },
  connectionPanel: {
    marginTop: 20,
    paddingTop: 16,
    borderTopWidth: 1,
    borderTopColor: COLORS.border,
  },
  connectionPanelTitle: {
    fontSize: 16,
    fontWeight: '700',
    color: COLORS.adminAccent,
    marginBottom: 12,
  },
  connectionRow: {
    fontSize: 14,
    color: COLORS.textSecondary,
    marginBottom: 10,
  },
  editSectionTitle: {
    fontSize: 16,
    fontWeight: '700',
    color: COLORS.adminAccent,
    marginTop: 16,
    marginBottom: 6,
  },
  editRow: {
    fontSize: 14,
    color: COLORS.text,
    marginBottom: 4,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    flexWrap: 'wrap',
    marginBottom: 8,
  },
  editLabel: {
    fontSize: 14,
    color: COLORS.textSecondary,
    marginRight: 8,
  },
  boxBtn: {
    paddingHorizontal: 10,
    paddingVertical: 6,
    marginRight: 6,
    borderRadius: 6,
    backgroundColor: COLORS.border,
  },
  boxBtnActive: {
    backgroundColor: COLORS.adminAccent,
  },
  boxBtnTextActive: {
    color: '#fff',
  },
  boxBtnText: {
    fontSize: 13,
    fontWeight: '600',
    color: COLORS.text,
  },
  tabBar: {
    flexDirection: 'row',
    marginBottom: 16,
    borderBottomWidth: 1,
    borderBottomColor: COLORS.border,
  },
  tab: {
    paddingVertical: 12,
    paddingHorizontal: 20,
    marginRight: 4,
  },
  tabActive: {
    borderBottomWidth: 2,
    borderBottomColor: COLORS.adminAccent,
  },
  tabText: {
    fontSize: 15,
    color: COLORS.textSecondary,
    fontWeight: '500',
  },
  tabTextActive: {
    color: COLORS.adminAccent,
  },
  logsSection: {
    flex: 1,
    minHeight: 360,
  },
  logsTitle: {
    fontSize: 18,
    fontWeight: '700',
    color: COLORS.adminAccent,
    marginBottom: 4,
  },
  logsHint: {
    fontSize: 13,
    color: COLORS.textSecondary,
    marginBottom: 12,
  },
  dashboardScroll: {
    flexGrow: 0,
    maxHeight: 520,
  },
  nextPrevRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 12,
  },
  nextPrevBtn: {
    paddingVertical: 8,
    paddingHorizontal: 14,
    borderRadius: 8,
    backgroundColor: COLORS.adminAccent,
  },
  nextPrevBtnDisabled: {
    opacity: 0.5,
  },
  nextPrevBtnText: {
    fontSize: 14,
    fontWeight: '600',
    color: '#fff',
  },
  nextPrevLabel: {
    fontSize: 13,
    color: COLORS.textSecondary,
  },
  inventorySectionLabel: {
    fontSize: 14,
    fontWeight: '700',
    color: COLORS.text,
    marginTop: 16,
    marginBottom: 6,
  },
  inventoryUserBlock: {
    marginTop: 8,
  },
  adherenceChartBlock: {
    paddingVertical: 12,
    alignItems: 'center',
  },
  adherenceChartValue: {
    fontSize: 28,
    fontWeight: '700',
    color: COLORS.adminAccent,
  },
  adherenceChartLabel: {
    fontSize: 13,
    color: COLORS.textSecondary,
    marginTop: 4,
  },
  logoutButton: {
    marginTop: 32,
    paddingVertical: 14,
    alignItems: 'center',
  },
  logoutText: {
    fontSize: 16,
    color: COLORS.error,
    fontWeight: '500',
  },
});
