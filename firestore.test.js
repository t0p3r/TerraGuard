const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_123";
const BOB_UID = "bob_uid_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validDevicePayload(uid) {
  const now = new Date(Date.now() - 1000);
  return {
    userId: uid,
    deviceName: "Pixel 9 Pro",
    deviceModel: "Google Pixel",
    isProtected: true,
    remoteAlarmActive: false,
    alarmOverrideSilent: true,
    latitude: 37.422,
    longitude: -122.084,
    accuracyMeters: 12.5,
    batteryPercent: 84,
    isCharging: false,
    isOnline: true,
    networkType: "Wi-Fi",
    simFingerprint: "sim_hash_abc",
    simCarrier: "T-Mobile",
    simAlertTriggered: false,
    ownerAlertEmail: "alice@example.com",
    lastLocationAtMillis: 1700000000000,
    createdAt: now,
    updatedAt: now,
  };
}

function validGeofencePayload(uid) {
  const now = new Date(Date.now() - 1000);
  return {
    userId: uid,
    name: "Home Base",
    latitude: 37.422,
    longitude: -122.084,
    radiusMeters: 250,
    isActive: true,
    lastStatus: "INSIDE",
    createdAt: now,
    updatedAt: now,
  };
}

function validAlertPayload(uid, deviceId) {
  const now = new Date(Date.now() - 1000);
  return {
    userId: uid,
    deviceId,
    deviceName: "Pixel 9 Pro",
    type: "GEOFENCE_EXIT",
    title: "Left Home Base",
    message: "Device exited perimeter at 84% battery.",
    latitude: 37.425,
    longitude: -122.089,
    accuracyMeters: 15.0,
    batteryPercent: 84,
    emailStatus: "QUEUED",
    hasPhotoEvidence: false,
    occurredAtMillis: 1700000001000,
    createdAt: now,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write devices, geofences, or alerts", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/devices`).get());
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/geofences`).get());
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/alerts`).get());
  await assertFails(unauthDb.doc(`users/${ALICE_UID}/devices/dev_1`).set(validDevicePayload(ALICE_UID)));
});

test("Authenticated owner can create and update device status and remote alarm", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const devRef = aliceDb.doc(`users/${ALICE_UID}/devices/device_1`);
  await assertSucceeds(devRef.set(validDevicePayload(ALICE_UID)));
  await assertSucceeds(devRef.update({
    remoteAlarmActive: true,
    alarmOverrideSilent: true,
    updatedAt: new Date(Date.now() - 500),
  }));
});

test("Cross-user access is denied for devices, locations, geofences, and alerts", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(aliceDb.doc(`users/${ALICE_UID}/devices/device_1`).set(validDevicePayload(ALICE_UID)));
  await assertSucceeds(aliceDb.doc(`users/${ALICE_UID}/geofences/geo_1`).set(validGeofencePayload(ALICE_UID)));

  await assertFails(bobDb.doc(`users/${ALICE_UID}/devices/device_1`).get());
  await assertFails(bobDb.doc(`users/${ALICE_UID}/devices/device_1`).update({
    remoteAlarmActive: true,
    updatedAt: new Date(Date.now() - 500),
  }));
  await assertFails(bobDb.collection(`users/${ALICE_UID}/geofences`).get());
  await assertFails(bobDb.doc(`users/${ALICE_UID}/alerts/alert_1`).set(validAlertPayload(BOB_UID, "device_1")));
});

test("Shadow update with ghost field and identity spoofing are rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const badPayload = { ...validDevicePayload(ALICE_UID), isAdmin: true };
  await assertFails(aliceDb.doc(`users/${ALICE_UID}/devices/device_1`).set(badPayload));

  const spoofPayload = validDevicePayload(BOB_UID);
  await assertFails(aliceDb.doc(`users/${ALICE_UID}/devices/device_2`).set(spoofPayload));
});

test("Location record requires existing parent device and rejects updates", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const orphanLoc = {
    userId: ALICE_UID,
    deviceId: "device_missing",
    latitude: 37.422,
    longitude: -122.084,
    accuracyMeters: 10.0,
    batteryPercent: 90,
    isOnline: true,
    provider: "Fused",
    sourceEvent: "MANUAL",
    recordedAtMillis: 1700000000000,
    createdAt: new Date(Date.now() - 1000),
  };
  await assertFails(aliceDb.doc(`users/${ALICE_UID}/devices/device_missing/locations/loc_1`).set(orphanLoc));

  await assertSucceeds(aliceDb.doc(`users/${ALICE_UID}/devices/device_1`).set(validDevicePayload(ALICE_UID)));
  const validLoc = { ...orphanLoc, deviceId: "device_1" };
  await assertSucceeds(aliceDb.doc(`users/${ALICE_UID}/devices/device_1/locations/loc_1`).set(validLoc));
  await assertFails(aliceDb.doc(`users/${ALICE_UID}/devices/device_1/locations/loc_1`).update({ batteryPercent: 50 }));
});

test("Geofence enum and radius boundaries + alert emailStatus update are enforced", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.doc(`users/${ALICE_UID}/geofences/geo_bad`).set({
    ...validGeofencePayload(ALICE_UID),
    radiusMeters: 10,
  }));
  await assertFails(aliceDb.doc(`users/${ALICE_UID}/geofences/geo_bad2`).set({
    ...validGeofencePayload(ALICE_UID),
    lastStatus: "INVALID_STATE",
  }));

  const alertRef = aliceDb.doc(`users/${ALICE_UID}/alerts/alert_1`);
  await assertSucceeds(alertRef.set(validAlertPayload(ALICE_UID, "device_1")));
  await assertSucceeds(alertRef.update({ emailStatus: "DISPATCHED" }));
  await assertFails(alertRef.update({ title: "Tampered Title" }));
});
