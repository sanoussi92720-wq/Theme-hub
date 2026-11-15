const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");
const path = require("node:path");
const firebase = require(path.resolve(
  path.dirname(require.resolve("@firebase/rules-unit-testing")),
  "../node_modules/firebase/compat/app"
));
require(path.resolve(
  path.dirname(require.resolve("@firebase/rules-unit-testing")),
  "../node_modules/firebase/compat/firestore"
));

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

const ADMIN_UID = "admin_uid_1";
const ALICE_UID = "alice_uid_1";
const BOB_UID = "bob_uid_2";
const ADMIN_EMAIL = "admin@example.com";
const ALICE_EMAIL = "alice@example.com";
const BOB_EMAIL = "bob@example.com";

const serverTs = () => firebase.firestore.FieldValue.serverTimestamp();
const tsSecondsAgo = (sec) => firebase.firestore.Timestamp.fromMillis(Date.now() - sec * 1000);

async function seedUser(uid, role = "user", blocked = false, name = "User") {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc(uid).set({
      uid,
      displayName: name,
      photoURL: "",
      bio: "",
      createdAt: tsSecondsAgo(60),
      role,
      blocked,
    });
  });
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
  if (testEnv) await testEnv.cleanup();
});

beforeEach(async () => {
  if (testEnv) await testEnv.clearFirestore();
});

// ---------------------------------------------------------------------------
// 1. User Profiles & Server Role Admin Security
// ---------------------------------------------------------------------------
test("User can create own profile as role=user, cannot self-assign role=admin or blocked=true", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, { email: ALICE_EMAIL, email_verified: true }).firestore();

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).set({
      uid: ALICE_UID,
      displayName: "Alice",
      photoURL: "",
      bio: "",
      createdAt: serverTs(),
      role: "admin",
      blocked: false,
    })
  );

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).set({
      uid: ALICE_UID,
      displayName: "Alice",
      photoURL: "",
      bio: "",
      createdAt: serverTs(),
      role: "user",
      blocked: true,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      uid: ALICE_UID,
      displayName: "Alice",
      photoURL: "",
      bio: "Hello!",
      createdAt: serverTs(),
      role: "user",
      blocked: false,
    })
  );
});

test("User cannot escalate own role or change another user's profile; Admin can toggle blocked", async () => {
  await seedUser(ALICE_UID, "user", false, "Alice");
  await seedUser(ADMIN_UID, "admin", false, "Admin");

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();

  // Owner updates bio within 150 chars -> succeeds
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).update({ bio: "Updated bio" }));

  // Owner bio > 150 chars -> fails
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).update({ bio: "a".repeat(151) }));

  // Owner tries to escalate role -> fails
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).update({ role: "admin" }));

  // Other user tries to update Alice -> fails
  await assertFails(bobDb.collection("users").doc(ALICE_UID).update({ bio: "Hacked" }));

  // Unfiltered list on users by non-admin fails; filtered role=='admin' succeeds
  await assertFails(aliceDb.collection("users").get());
  await assertSucceeds(aliceDb.collection("users").where("role", "==", "admin").limit(1).get());

  // Admin can list users and block Alice
  await assertSucceeds(adminDb.collection("users").get());
  await assertSucceeds(adminDb.collection("users").doc(ALICE_UID).update({ blocked: true }));
});

// ---------------------------------------------------------------------------
// 2. Ratings & Aggregate Transactions
// ---------------------------------------------------------------------------
test("Guests can read ratings; signed-in user can rate 1-5 atomically; blocked user cannot rate", async () => {
  await seedUser(ALICE_UID, "user", false, "Alice");
  await seedUser(BOB_UID, "user", true, "BlockedBob");

  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(unauthDb.collection("themes").get());

  // Guest cannot rate
  await assertFails(
    unauthDb.collection("themes").doc("space-1").collection("ratings").doc("anon").set({
      stars: 5,
      updatedAt: serverTs(),
    })
  );

  // Alice rates space-1 via batch/transaction
  const batch = aliceDb.batch();
  batch.set(aliceDb.collection("themes").doc("space-1").collection("ratings").doc(ALICE_UID), {
    stars: 5,
    updatedAt: serverTs(),
  });
  batch.set(aliceDb.collection("themes").doc("space-1"), {
    ratingSum: 5,
    ratingCount: 1,
    ratingAvg: 5,
    updatedAt: serverTs(),
  });
  await assertSucceeds(batch.commit());

  // Tampered stats (inflated count or sum without matching delta) fail
  await assertFails(
    aliceDb.collection("themes").doc("space-1").update({
      ratingCount: 50,
      ratingSum: 250,
      ratingAvg: 5,
      updatedAt: serverTs(),
    })
  );

  // Blocked Bob cannot rate
  const bobBatch = bobDb.batch();
  bobBatch.set(bobDb.collection("themes").doc("space-1").collection("ratings").doc(BOB_UID), {
    stars: 1,
    updatedAt: serverTs(),
  });
  bobBatch.set(
    bobDb.collection("themes").doc("space-1"),
    { ratingSum: 6, ratingCount: 2, ratingAvg: 3, updatedAt: serverTs() },
    { merge: true }
  );
  await assertFails(bobBatch.commit());
});

// ---------------------------------------------------------------------------
// 3. Comments, Likes, Reports & Moderation
// ---------------------------------------------------------------------------
test("Comments: public read, 500-char max, owner/admin delete only, blocked cannot comment", async () => {
  await seedUser(ALICE_UID, "user", false, "Alice");
  await seedUser(BOB_UID, "user", true, "BlockedBob");
  await seedUser(ADMIN_UID, "admin", false, "Admin");

  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();

  await assertSucceeds(unauthDb.collection("themes").doc("space-1").collection("comments").limit(20).get());

  // > 500 chars fails
  await assertFails(
    aliceDb.collection("themes").doc("space-1").collection("comments").doc("c1").set({
      uid: ALICE_UID,
      name: "Alice",
      photo: "",
      text: "x".repeat(501),
      createdAt: serverTs(),
      likes: 0,
    })
  );

  // Valid comment succeeds
  await assertSucceeds(
    aliceDb.collection("themes").doc("space-1").collection("comments").doc("c1").set({
      uid: ALICE_UID,
      name: "Alice",
      photo: "",
      text: "Amazing Honor theme!",
      createdAt: serverTs(),
      likes: 0,
    })
  );

  // Blocked user cannot comment
  await assertFails(
    bobDb.collection("themes").doc("space-1").collection("comments").doc("c2").set({
      uid: BOB_UID,
      name: "Bob",
      photo: "",
      text: "Spam",
      createdAt: serverTs(),
      likes: 0,
    })
  );

  // Bob cannot delete Alice's comment
  await assertFails(bobDb.collection("themes").doc("space-1").collection("comments").doc("c1").delete());

  // Admin can delete any comment
  await assertSucceeds(adminDb.collection("themes").doc("space-1").collection("comments").doc("c1").delete());
});

// ---------------------------------------------------------------------------
// 4. Private Messaging (User <-> Admin Only + 2s Rate Limit)
// ---------------------------------------------------------------------------
test("Private chat: user can only read/write own conversation with admin, 2s rate-limit enforced", async () => {
  await seedUser(ALICE_UID, "user", false, "Alice");
  await seedUser(BOB_UID, "user", false, "Bob");
  await seedUser(ADMIN_UID, "admin", false, "Admin");

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();

  // First message by Alice atomically creates conversation & message
  const b1 = aliceDb.batch();
  b1.set(aliceDb.collection("conversations").doc(ALICE_UID), {
    uid: ALICE_UID,
    userName: "Alice",
    userPhoto: "",
    lastMessage: "Hello Admin!",
    lastMessageAt: serverTs(),
    lastUserMessageAt: serverTs(),
    lastSenderId: ALICE_UID,
    unreadByAdmin: 1,
    unreadByUser: 0,
  });
  b1.set(aliceDb.collection("conversations").doc(ALICE_UID).collection("messages").doc("m1"), {
    text: "Hello Admin!",
    senderId: ALICE_UID,
    createdAt: serverTs(),
    read: false,
  });
  await assertSucceeds(b1.commit());

  // Immediate spam (< 2s) fails!
  const bSpam = aliceDb.batch();
  bSpam.update(aliceDb.collection("conversations").doc(ALICE_UID), {
    userName: "Alice",
    userPhoto: "",
    lastMessage: "Spam!",
    lastMessageAt: serverTs(),
    lastUserMessageAt: serverTs(),
    lastSenderId: ALICE_UID,
    unreadByAdmin: 2,
    unreadByUser: 0,
  });
  bSpam.set(aliceDb.collection("conversations").doc(ALICE_UID).collection("messages").doc("m2"), {
    text: "Spam!",
    senderId: ALICE_UID,
    createdAt: serverTs(),
    read: false,
  });
  await assertFails(bSpam.commit());

  // Bob cannot read Alice's conversation or messages
  await assertFails(bobDb.collection("conversations").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("conversations").doc(ALICE_UID).collection("messages").get());

  // Bob cannot write into Alice's conversation
  await assertFails(
    bobDb.collection("conversations").doc(ALICE_UID).collection("messages").doc("m3").set({
      text: "Hi Alice",
      senderId: BOB_UID,
      createdAt: serverTs(),
      read: false,
    })
  );

  // Admin can list conversations, read Alice's thread, and reply
  await assertSucceeds(adminDb.collection("conversations").get());
  await assertSucceeds(adminDb.collection("conversations").doc(ALICE_UID).collection("messages").get());
  await assertSucceeds(
    adminDb.collection("conversations").doc(ALICE_UID).collection("messages").doc("m_reply").set({
      text: "Welcome Alice!",
      senderId: ADMIN_UID,
      createdAt: serverTs(),
      read: false,
    })
  );
});

// ---------------------------------------------------------------------------
// 5. Admins Registry & GitHub Config (Server Role-Based, No Hardcoded Email)
// ---------------------------------------------------------------------------
test("admins/{email} and config/github respect server-side users/{uid}.role == admin", async () => {
  await seedUser(ALICE_UID, "user", false, "Alice");
  await seedUser(ADMIN_UID, "admin", false, "Admin");

  const aliceDb = testEnv.authenticatedContext(ALICE_UID, { email: ALICE_EMAIL, email_verified: true }).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, { email: ADMIN_EMAIL, email_verified: true }).firestore();

  await assertFails(aliceDb.collection("config").doc("github").get());
  await assertFails(aliceDb.collection("config").doc("github").set({ token: "hacked" }));
  await assertFails(aliceDb.collection("admins").doc(ALICE_EMAIL).set({ email: ALICE_EMAIL }));

  await assertSucceeds(adminDb.collection("config").doc("github").set({ token: "ghp_real_token" }));
  await assertSucceeds(adminDb.collection("config").doc("github").get());
  await assertSucceeds(adminDb.collection("admins").doc(ALICE_EMAIL).set({ email: ALICE_EMAIL }));
});
