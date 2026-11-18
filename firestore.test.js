const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

const PAST_TS = () => new Date(Date.now() - 2000);
const serverTimestamp = () => PAST_TS();

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "veluna-ci-test";
const ALICE_UID = "alice_uid_123";
const BOB_UID = "bob_uid_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validProfilePayload(uid, overrides = {}) {
  return {
    userId: uid,
    displayName: "Alice Listener",
    email: "alice@example.com",
    photoUrl: "https://example.com/avatar.jpg",
    themePreset: "default",
    themeMode: "DARK",
    adaptToArtwork: true,
    cornerRadiusDp: 12,
    audioQuality: "HIGH",
    autoplay: true,
    loudnessNormalization: true,
    hideMusicVideos: false,
    selectedGenres: ["Indie", "Synthwave"],
    onboardingCompleted: true,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    ...overrides,
  };
}

function validLikedSongPayload(overrides = {}) {
  return {
    title: "Midnight City",
    artists: "M83",
    album: "Hurry Up, We're Dreaming",
    albumId: "MPREb_12345",
    artistId: "UC1234567890",
    duration: "4:03",
    durationSecs: 243,
    thumbnail: "https://lh3.googleusercontent.com/abc=w720-h720-l90-rj",
    explicit: false,
    likedAt: 1700000000000,
    deleted: false,
    clientUpdatedAt: 1700000000000,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    ...overrides,
  };
}

function validPlaylistPayload(overrides = {}) {
  return {
    title: "Late Night Drive",
    description: "Synth and ambient picks",
    deleted: false,
    clientUpdatedAt: 1700000000000,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    ...overrides,
  };
}

function validPlaylistTrackPayload(overrides = {}) {
  return {
    videoId: "dQw4w9WgXcQ",
    title: "Track One",
    artists: "Artist A",
    album: "Album A",
    duration: "3:33",
    durationSecs: 213,
    explicit: false,
    position: 0,
    addedAt: 1700000000000,
    deleted: false,
    clientUpdatedAt: 1700000000000,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    ...overrides,
  };
}

function validTombstonePayload(overrides = {}) {
  return {
    deleted: true,
    clientUpdatedAt: 1700000005000,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    ...overrides,
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

test("Unauthenticated caller cannot read or write any collection or subcollection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set(validProfilePayload(ALICE_UID))
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("liked_songs").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("playlists").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("playlists")
      .doc("pl_1")
      .collection("tracks")
      .get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("taste_seeds").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("hidden_artists").get()
  );
});

test("Owner can create, read, and update their profile; shadow fields and userId spoofing fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const profileRef = aliceDb.collection("users").doc(ALICE_UID);

  await assertSucceeds(profileRef.set(validProfilePayload(ALICE_UID)));
  await assertSucceeds(profileRef.get());

  // Shadow update with unknown field must fail
  await assertFails(profileRef.update({ isAdmin: true, updatedAt: serverTimestamp() }));

  // Update mutable displayName succeeds
  await assertSucceeds(
    profileRef.update({ displayName: "Alice Updated", updatedAt: serverTimestamp() })
  );

  // Overwriting immutable createdAt must fail
  await assertFails(
    profileRef.update({ createdAt: serverTimestamp(), updatedAt: serverTimestamp() })
  );

  // Identity spoofing on create must fail
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb.collection("users").doc(BOB_UID).set(validProfilePayload(ALICE_UID))
  );
});

test("Cross-user reads and writes are strictly rejected across collections and subcollections", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const aliceUser = aliceDb.collection("users").doc(ALICE_UID);
  await assertSucceeds(aliceUser.set(validProfilePayload(ALICE_UID)));
  await assertSucceeds(
    aliceUser.collection("liked_songs").doc("dQw4w9WgXcQ").set(validLikedSongPayload())
  );
  await assertSucceeds(
    aliceUser.collection("playlists").doc("pl_1").set(validPlaylistPayload())
  );
  await assertSucceeds(
    aliceUser
      .collection("playlists")
      .doc("pl_1")
      .collection("tracks")
      .doc("dQw4w9WgXcQ")
      .set(validPlaylistTrackPayload())
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const bobTargetsAlice = bobDb.collection("users").doc(ALICE_UID);
  await assertFails(bobTargetsAlice.get());
  await assertFails(bobTargetsAlice.collection("liked_songs").get());
  await assertFails(
    bobTargetsAlice.collection("liked_songs").doc("dQw4w9WgXcQ").delete()
  );
  await assertFails(
    bobTargetsAlice.collection("playlists").doc("pl_1").collection("tracks").get()
  );
  await assertFails(
    bobTargetsAlice
      .collection("playlists")
      .doc("pl_1")
      .collection("tracks")
      .doc("other")
      .set(validPlaylistTrackPayload())
  );
  await assertFails(bobTargetsAlice.delete());
});

test("Owner can manage liked_songs, playlists + tracks subcollection, taste_seeds, and hidden_artists; rejects LOCAL tracks and legacy arrays", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const userDoc = aliceDb.collection("users").doc(ALICE_UID);

  await assertSucceeds(
    userDoc.collection("liked_songs").doc("track_123").set(validLikedSongPayload())
  );
  await assertSucceeds(userDoc.collection("liked_songs").get());

  // LOCAL-prefixed track IDs must be rejected on liked_songs and playlist tracks
  await assertFails(
    userDoc.collection("liked_songs").doc("LOCAL_abc").set(validLikedSongPayload())
  );

  // Playlists must NOT carry legacy tracks array on create
  await assertFails(
    userDoc.collection("playlists").doc("pl_bad").set(
      validPlaylistPayload({ tracks: [{ videoId: "abc", title: "X" }] })
    )
  );

  const plRef = userDoc.collection("playlists").doc("pl_night_drive");
  await assertSucceeds(plRef.set(validPlaylistPayload()));
  await assertSucceeds(userDoc.collection("playlists").get());

  // Playlist update adding legacy tracks array must fail
  await assertFails(
    plRef.update({ tracks: [{ videoId: "abc" }], updatedAt: serverTimestamp() })
  );

  // Subcollection track create/update succeeds; LOCAL:* videoId rejected
  const trkRef = plRef.collection("tracks").doc("dQw4w9WgXcQ");
  await assertSucceeds(trkRef.set(validPlaylistTrackPayload()));
  await assertSucceeds(plRef.collection("tracks").get());
  await assertFails(
    plRef
      .collection("tracks")
      .doc("trk_local_1")
      .set(validPlaylistTrackPayload({ videoId: "LOCAL:content://audio/1" }))
  );

  await assertSucceeds(
    userDoc.collection("taste_seeds").doc("seed_uc123").set({
      kind: "ARTIST",
      name: "Daft Punk",
      subtitle: "Electronic",
      deleted: false,
      clientUpdatedAt: 1700000000000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  await assertSucceeds(
    userDoc.collection("hidden_artists").doc("artist_abc").set({
      name: "Some Artist",
      blockedAt: 1700000000000,
      deleted: false,
      clientUpdatedAt: 1700000000000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  // Invalid bounds or types fail
  await assertFails(
    userDoc.collection("liked_songs").doc("bad_track").set(validLikedSongPayload({ durationSecs: -5 }))
  );
  await assertFails(
    userDoc.collection("taste_seeds").doc("bad_kind").set({
      kind: "PODCAST",
      name: "Bad",
      deleted: false,
      clientUpdatedAt: 1700000000000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    userDoc.collection("playlists").doc("pl_too_long").set(
      validPlaylistPayload({ title: "A".repeat(121) })
    )
  );
});

test("Tombstone shapes succeed on create and update; malformed tombstones fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const userDoc = aliceDb.collection("users").doc(ALICE_UID);

  // Tombstone create directly on liked_songs & taste_seeds
  await assertSucceeds(
    userDoc.collection("liked_songs").doc("removed_1").set(validTombstonePayload())
  );
  await assertSucceeds(
    userDoc.collection("taste_seeds").doc("art_old").set(validTombstonePayload())
  );

  // Tombstone update on existing playlist & playlist track & hidden artist
  const plRef = userDoc.collection("playlists").doc("pl_tomb");
  await assertSucceeds(plRef.set(validPlaylistPayload()));
  await assertSucceeds(
    plRef.collection("tracks").doc("trk_1").set(validPlaylistTrackPayload({ videoId: "trk_1" }))
  );
  await assertSucceeds(
    plRef.collection("tracks").doc("trk_1").update({
      deleted: true,
      clientUpdatedAt: 1700000009000,
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(
    plRef.update({
      deleted: true,
      clientUpdatedAt: 1700000009000,
      updatedAt: serverTimestamp(),
    })
  );

  const blkRef = userDoc.collection("hidden_artists").doc("blk_1");
  await assertSucceeds(
    blkRef.set({
      name: "Blocked",
      blockedAt: 1700000000000,
      deleted: false,
      clientUpdatedAt: 1700000000000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(
    blkRef.update({
      deleted: true,
      clientUpdatedAt: 1700000010000,
      updatedAt: serverTimestamp(),
    })
  );

  // Invalid tombstones fail (extra field, deleted:false, negative timestamp)
  await assertFails(
    userDoc
      .collection("liked_songs")
      .doc("bad_tomb")
      .set(validTombstonePayload({ extraField: "nope" }))
  );
  await assertFails(
    userDoc
      .collection("liked_songs")
      .doc("bad_tomb_2")
      .set(validTombstonePayload({ clientUpdatedAt: -1 }))
  );
});

test("Account deletion batch wipe succeeds for owner across subcollections and root profile", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const userDoc = aliceDb.collection("users").doc(ALICE_UID);

  await assertSucceeds(userDoc.set(validProfilePayload(ALICE_UID)));
  await assertSucceeds(
    userDoc.collection("liked_songs").doc("trk_del").set(validLikedSongPayload())
  );
  await assertSucceeds(
    userDoc.collection("playlists").doc("pl_del").set(validPlaylistPayload())
  );
  await assertSucceeds(
    userDoc
      .collection("playlists")
      .doc("pl_del")
      .collection("tracks")
      .doc("trk_del")
      .set(validPlaylistTrackPayload({ videoId: "trk_del" }))
  );

  await assertSucceeds(
    userDoc.collection("playlists").doc("pl_del").collection("tracks").doc("trk_del").delete()
  );
  await assertSucceeds(userDoc.collection("playlists").doc("pl_del").delete());
  await assertSucceeds(userDoc.collection("liked_songs").doc("trk_del").delete());
  await assertSucceeds(userDoc.delete());
});

test("Playlist versioned snapshots enforce owner-only access, valid state transitions, and snapshot tracks rules", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const plRef = aliceDb.collection("users").doc(ALICE_UID).collection("playlists").doc("pl_snap");
  const snapRef = plRef.collection("snapshots").doc("snap_1_abc123");
  const trackRef = snapRef.collection("tracks").doc("trk_00000_dQw4w9WgXcQ");

  await assertSucceeds(
    snapRef.set({
      state: "STAGING",
      syncRevision: 1,
      trackCount: 1,
      clientUpdatedAt: 1700000000000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(trackRef.set(validPlaylistTrackPayload()));
  await assertSucceeds(
    snapRef.update({
      state: "COMMITTED",
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(
    plRef.set(
      validPlaylistPayload({
        syncRevision: 1,
        activeSnapshotId: "snap_1_abc123",
        trackCount: 1,
      })
    )
  );
  await assertSucceeds(snapRef.collection("tracks").get());

  // Cannot mutate syncRevision after snapshot creation or use invalid state
  await assertFails(
    snapRef.update({
      syncRevision: 2,
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    plRef.collection("snapshots").doc("snap_bad").set({
      state: "UNKNOWN",
      syncRevision: 1,
      trackCount: 0,
      clientUpdatedAt: 1700000000000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  // LOCAL track rejected inside snapshot tracks
  await assertFails(
    snapRef.collection("tracks").doc("LOCAL_trk").set(validPlaylistTrackPayload())
  );

  // Cross-user access to snapshots or snapshot tracks fails
  const bobPlRef = bobDb.collection("users").doc(ALICE_UID).collection("playlists").doc("pl_snap");
  await assertFails(bobPlRef.collection("snapshots").get());
  await assertFails(bobPlRef.collection("snapshots").doc("snap_1_abc123").collection("tracks").get());
  await assertFails(bobPlRef.collection("snapshots").doc("snap_1_abc123").delete());

  // Owner cleanup succeeds
  await assertSucceeds(trackRef.delete());
  await assertSucceeds(snapRef.delete());
});

test("Client timestamps accept bounded skew (<= 5 min) and reject far-future values across collections", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const userDoc = aliceDb.collection("users").doc(ALICE_UID);
  const now = Date.now();
  const validSkewMs = now + 60_000;
  const invalidFutureMs = now + 600_000;

  await assertSucceeds(
    userDoc.set(validProfilePayload(ALICE_UID, { prefsUpdatedAt: validSkewMs }))
  );
  await assertFails(
    userDoc.update({ prefsUpdatedAt: invalidFutureMs, updatedAt: serverTimestamp() })
  );

  await assertSucceeds(
    userDoc.collection("liked_songs").doc("skew_ok").set(validLikedSongPayload({ clientUpdatedAt: validSkewMs }))
  );
  await assertFails(
    userDoc.collection("liked_songs").doc("skew_bad").set(validLikedSongPayload({ clientUpdatedAt: invalidFutureMs }))
  );
  await assertFails(
    userDoc.collection("playlists").doc("pl_future").set(validPlaylistPayload({ clientUpdatedAt: invalidFutureMs }))
  );
  await assertFails(
    userDoc.collection("taste_seeds").doc("art_future").set({
      kind: "ARTIST",
      name: "Future Artist",
      deleted: false,
      clientUpdatedAt: invalidFutureMs,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    userDoc.collection("hidden_artists").doc("blk_future").set({
      name: "Future Hidden",
      blockedAt: now,
      deleted: false,
      clientUpdatedAt: invalidFutureMs,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    userDoc.collection("liked_songs").doc("tomb_future").set(validTombstonePayload({ clientUpdatedAt: invalidFutureMs }))
  );
});

test("Compound tombstone query (deleted == true, clientUpdatedAt < cutoff) succeeds for owner", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const userDoc = aliceDb.collection("users").doc(ALICE_UID);
  const cutoff = 1700000010000;

  for (const col of ["liked_songs", "playlists", "taste_seeds", "hidden_artists"]) {
    await assertSucceeds(
      userDoc.collection(col).doc("old_tomb").set(validTombstonePayload({ clientUpdatedAt: cutoff - 5000 }))
    );
    const snap = await assertSucceeds(
      userDoc.collection(col).where("deleted", "==", true).where("clientUpdatedAt", "<", cutoff).get()
    );
    if (snap.size !== 1) {
      throw new Error(`Expected 1 expired tombstone in ${col}, got ${snap.size}`);
    }
  }
});
