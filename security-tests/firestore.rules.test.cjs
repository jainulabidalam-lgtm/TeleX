const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require("@firebase/rules-unit-testing");

const {
  doc,
  setDoc,
  updateDoc,
  getDoc,
  deleteField,
} = require("firebase/firestore");

const fs = require("node:fs");
const path = require("node:path");

const PROJECT_ID = "telex-7f690";

let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      host: "127.0.0.1",
      port: 8080,
      rules: fs.readFileSync(
        path.join(__dirname, "..", "firestore.rules.candidate"),
        "utf8"
      ),
    },
  });
});

after(async () => {
  await testEnv.cleanup();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
});

async function seed(path, data) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), path), data);
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// SECTION 1 — Chat Participant Injection (CVE-class: participant injection)
// ─────────────────────────────────────────────────────────────────────────────

it("[DENY] existing participant cannot inject a new chat participant", async () => {
  await seed("chats/chat1", {
    participants: ["alice", "bob"],
    lastMessage: "",
  });

  const alice = testEnv.authenticatedContext("alice").firestore();

  await assertFails(
    updateDoc(doc(alice, "chats/chat1"), {
      participants: ["alice", "bob", "mallory"],
    })
  );
});

it("[DENY] existing participant cannot remove another participant from the array", async () => {
  await seed("chats/chat1", {
    participants: ["alice", "bob"],
    lastMessage: "",
  });

  const alice = testEnv.authenticatedContext("alice").firestore();

  await assertFails(
    updateDoc(doc(alice, "chats/chat1"), {
      participants: ["alice"],
    })
  );
});

it("[DENY] existing participant cannot replace participants with a different order", async () => {
  await seed("chats/chat1", {
    participants: ["alice", "bob"],
    lastMessage: "",
  });

  const alice = testEnv.authenticatedContext("alice").firestore();

  await assertFails(
    updateDoc(doc(alice, "chats/chat1"), {
      participants: ["bob", "alice"],
    })
  );
});

it("[ALLOW] participant can update chat metadata without touching participants", async () => {
  await seed("chats/chat1", {
    participants: ["alice", "bob"],
    lastMessage: "Hello",
    lastMessageTimestamp: 1000,
  });

  const alice = testEnv.authenticatedContext("alice").firestore();

  await assertSucceeds(
    updateDoc(doc(alice, "chats/chat1"), {
      lastMessage: "Encrypted message",
      lastMessageTimestamp: 2000,
    })
  );
});

it("[DENY] unauthenticated caller cannot create a chat", async () => {
  const anon = testEnv.unauthenticatedContext().firestore();

  await assertFails(
    setDoc(doc(anon, "chats/chatX"), {
      participants: ["alice", "bob"],
      lastMessage: "",
    })
  );
});

it("[DENY] non-participant cannot read a chat", async () => {
  await seed("chats/chat1", {
    participants: ["alice", "bob"],
    lastMessage: "",
  });

  const mallory = testEnv.authenticatedContext("mallory").firestore();

  await assertFails(getDoc(doc(mallory, "chats/chat1")));
});

// ─────────────────────────────────────────────────────────────────────────────
// SECTION 2 — Prekey Value Tampering (CVE-class: retained-value mutation)
// ─────────────────────────────────────────────────────────────────────────────

it("[DENY] prekey consumption cannot modify values of retained keys (map replacement)", async () => {
  await seed("users/bob/prekey_bundle/current", {
    identityKey: "identity-original",
    oneTimePreKeys: {
      key1: "value1",
      key2: "value2",
      key3: "value3",
    },
  });

  const alice = testEnv.authenticatedContext("alice").firestore();

  // Remove key1 but tamper with key2 value — must be rejected.
  await assertFails(
    updateDoc(doc(alice, "users/bob/prekey_bundle/current"), {
      oneTimePreKeys: {
        key2: "TAMPERED",
        key3: "value3",
      },
    })
  );
});

it("[DENY] prekey consumption cannot inject new keys while removing one", async () => {
  await seed("users/bob/prekey_bundle/current", {
    identityKey: "identity-original",
    oneTimePreKeys: {
      key1: "value1",
      key2: "value2",
    },
  });

  const alice = testEnv.authenticatedContext("alice").firestore();

  // Remove key1, but also inject key3 — must be rejected.
  await assertFails(
    updateDoc(doc(alice, "users/bob/prekey_bundle/current"), {
      oneTimePreKeys: {
        key2: "value2",
        key3: "injected-key",
      },
    })
  );
});

// ─────────────────────────────────────────────────────────────────────────────
// SECTION 3 — Bulk Prekey Removal (CVE-class: prekey exhaustion in one write)
// ─────────────────────────────────────────────────────────────────────────────

it("[DENY] peer cannot bulk-remove multiple prekeys in one operation", async () => {
  await seed("users/bob/prekey_bundle/current", {
    identityKey: "identity-original",
    oneTimePreKeys: {
      key1: "value1",
      key2: "value2",
      key3: "value3",
    },
  });

  const alice = testEnv.authenticatedContext("alice").firestore();

  // Attempt to remove two keys at once — must be rejected.
  await assertFails(
    updateDoc(doc(alice, "users/bob/prekey_bundle/current"), {
      oneTimePreKeys: {
        key3: "value3",
      },
    })
  );
});

it("[ALLOW] removing the last remaining prekey (single removal) is still a valid operation", async () => {
  await seed("users/bob/prekey_bundle/current", {
    identityKey: "identity-original",
    oneTimePreKeys: {
      key1: "value1",
    },
  });

  const alice = testEnv.authenticatedContext("alice").firestore();

  // Map replacement with empty object removes the last key — would be bulk if multiple existed.
  // Here only one key exists, so this is a valid single removal:
  await assertSucceeds(
    updateDoc(doc(alice, "users/bob/prekey_bundle/current"), {
      oneTimePreKeys: {},
    })
  );
});

// ─────────────────────────────────────────────────────────────────────────────
// SECTION 4 — Legitimate Prekey Consumption Regression Tests
// ─────────────────────────────────────────────────────────────────────────────

it("[ALLOW] peer can legitimately consume exactly one prekey via map replacement", async () => {
  await seed("users/bob/prekey_bundle/current", {
    identityKey: "identity-original",
    oneTimePreKeys: {
      key1: "value1",
      key2: "value2",
      key3: "value3",
    },
  });

  const alice = testEnv.authenticatedContext("alice").firestore();

  // Alice consumes key1, leaving key2 and key3 with their original values.
  await assertSucceeds(
    updateDoc(doc(alice, "users/bob/prekey_bundle/current"), {
      oneTimePreKeys: {
        key2: "value2",
        key3: "value3",
      },
    })
  );
});

it("[ALLOW] peer can legitimately consume exactly one prekey via FieldValue.delete() (actual TeleX KeyManager flow)", async () => {
  // This is the real flow used by KeyManager.kt:
  //   transaction.update(bundleRef, "oneTimePreKeys.$keyIdStr", FieldValue.delete())
  await seed("users/bob/prekey_bundle/current", {
    identityKey: "identity-original",
    oneTimePreKeys: {
      key1: "value1",
      key2: "value2",
      key3: "value3",
    },
  });

  const alice = testEnv.authenticatedContext("alice").firestore();

  // FieldValue.delete() on a dotted path removes exactly one key atomically.
  await assertSucceeds(
    updateDoc(doc(alice, "users/bob/prekey_bundle/current"), {
      "oneTimePreKeys.key1": deleteField(),
    })
  );
});

it("[DENY] no-op update (same prekey map, nothing removed) should be rejected", async () => {
  await seed("users/bob/prekey_bundle/current", {
    identityKey: "identity-original",
    oneTimePreKeys: {
      key1: "value1",
      key2: "value2",
    },
  });

  const alice = testEnv.authenticatedContext("alice").firestore();

  // exactlyOneRemoved constraint requires removedKeys().size() == 1; 0 removals → denied.
  await assertFails(
    updateDoc(doc(alice, "users/bob/prekey_bundle/current"), {
      oneTimePreKeys: {
        key1: "value1",
        key2: "value2",
      },
    })
  );
});

it("[DENY] peer cannot touch identityKey even while removing a prekey", async () => {
  await seed("users/bob/prekey_bundle/current", {
    identityKey: "identity-original",
    oneTimePreKeys: {
      key1: "value1",
      key2: "value2",
    },
  });

  const alice = testEnv.authenticatedContext("alice").firestore();

  // Attempt to mutate identityKey alongside a legitimate prekey removal.
  await assertFails(
    updateDoc(doc(alice, "users/bob/prekey_bundle/current"), {
      identityKey: "ATTACKER-KEY",
      "oneTimePreKeys.key1": deleteField(),
    })
  );
});

it("[DENY] peer cannot add a brand-new prekey via FieldValue-style dotted path set", async () => {
  await seed("users/bob/prekey_bundle/current", {
    identityKey: "identity-original",
    oneTimePreKeys: {
      key1: "value1",
    },
  });

  const alice = testEnv.authenticatedContext("alice").firestore();

  // Setting a new dotted-path key in the map (addedKeys() == 1 → denied).
  await assertFails(
    updateDoc(doc(alice, "users/bob/prekey_bundle/current"), {
      "oneTimePreKeys.injected": "evil",
    })
  );
});

// ─────────────────────────────────────────────────────────────────────────────
// SECTION 5 — Owner Bundle Update Regression Tests
// ─────────────────────────────────────────────────────────────────────────────

it("[ALLOW] bundle owner can fully replenish their own prekey bundle (update)", async () => {
  await seed("users/bob/prekey_bundle/current", {
    identityKey: "identity-original",
    signedPreKey: "signed-1",
    oneTimePreKeys: {
      key1: "value1",
    },
  });

  const bob = testEnv.authenticatedContext("bob").firestore();

  // Bob replenishes prekeys and rotates signedPreKey — owner is allowed.
  await assertSucceeds(
    updateDoc(doc(bob, "users/bob/prekey_bundle/current"), {
      signedPreKey: "signed-2",
      oneTimePreKeys: {
        key1: "value1",
        key2: "value2",
        key3: "value3",
        key4: "value4",
      },
    })
  );
});

it("[ALLOW] bundle owner can publish initial bundle via setDoc (publishPublicPreKeyBundle flow)", async () => {
  const bob = testEnv.authenticatedContext("bob").firestore();

  await assertSucceeds(
    setDoc(doc(bob, "users/bob/prekey_bundle/current"), {
      registrationId: 12345,
      deviceId: 1,
      identityKey: "bob-identity",
      signedPreKeyId: 1,
      signedPreKey: "bob-signed",
      signedPreKeySignature: "bob-sig",
      kyberPreKeyId: 1,
      kyberPreKey: "bob-kyber",
      kyberPreKeySignature: "bob-kyber-sig",
      oneTimePreKeys: {
        key1: "val1",
        key2: "val2",
      },
      updatedAt: 1000000,
    })
  );
});

it("[DENY] non-owner cannot publish or overwrite another user's bundle", async () => {
  await seed("users/bob/prekey_bundle/current", {
    identityKey: "identity-original",
    oneTimePreKeys: { key1: "value1" },
  });

  const mallory = testEnv.authenticatedContext("mallory").firestore();

  // Mallory attempting a full setDoc overwrite of Bob's bundle.
  await assertFails(
    setDoc(doc(mallory, "users/bob/prekey_bundle/current"), {
      identityKey: "mallory-injected",
      oneTimePreKeys: { key1: "evil" },
    })
  );
});

it("[DENY] non-owner cannot updateDoc arbitrary fields on another user's bundle", async () => {
  await seed("users/bob/prekey_bundle/current", {
    identityKey: "identity-original",
    oneTimePreKeys: { key1: "value1" },
  });

  const mallory = testEnv.authenticatedContext("mallory").firestore();

  await assertFails(
    updateDoc(doc(mallory, "users/bob/prekey_bundle/current"), {
      identityKey: "mallory-injected",
    })
  );
});

it("[ALLOW] any authenticated peer can read a user's bundle (E2EE session setup)", async () => {
  await seed("users/bob/prekey_bundle/current", {
    identityKey: "identity-original",
    oneTimePreKeys: { key1: "value1" },
  });

  const alice = testEnv.authenticatedContext("alice").firestore();

  await assertSucceeds(getDoc(doc(alice, "users/bob/prekey_bundle/current")));
});

it("[DENY] unauthenticated caller cannot read any bundle", async () => {
  await seed("users/bob/prekey_bundle/current", {
    identityKey: "identity-original",
    oneTimePreKeys: { key1: "value1" },
  });

  const anon = testEnv.unauthenticatedContext().firestore();

  await assertFails(getDoc(doc(anon, "users/bob/prekey_bundle/current")));
});

// ─────────────────────────────────────────────────────────────────────────────
// SECTION 6 — Phase 2: User Profile Input Validation & Abuse
// ─────────────────────────────────────────────────────────────────────────────

it("[DENY] user cannot set invalid username (too short, uppercase, or invalid characters)", async () => {
  const alice = testEnv.authenticatedContext("alice").firestore();

  // Too short (< 3 chars)
  await assertFails(
    setDoc(doc(alice, "users/alice"), {
      username: "al",
      dob: "01/01/2000",
      email: "alice@example.com",
    })
  );

  // Contains uppercase or invalid symbols
  await assertFails(
    setDoc(doc(alice, "users/alice"), {
      username: "Alice_123!",
      dob: "01/01/2000",
      email: "alice@example.com",
    })
  );
});

it("[DENY] user cannot set oversized username (> 30 characters)", async () => {
  const alice = testEnv.authenticatedContext("alice").firestore();

  await assertFails(
    setDoc(doc(alice, "users/alice"), {
      username: "a".repeat(31),
      dob: "01/01/2000",
      email: "alice@example.com",
    })
  );
});

it("[DENY] user cannot inject arbitrary unknown fields into profile", async () => {
  const alice = testEnv.authenticatedContext("alice").firestore();

  await assertFails(
    setDoc(doc(alice, "users/alice"), {
      username: "alice_clean",
      dob: "01/01/2000",
      email: "alice@example.com",
      role: "admin", // Malicious field injection
      injectedPayload: "x".repeat(1000),
    })
  );
});

it("[ALLOW] user can create and update legitimate profile", async () => {
  const alice = testEnv.authenticatedContext("alice").firestore();

  await assertSucceeds(
    setDoc(doc(alice, "users/alice"), {
      username: "alice_clean",
      dob: "01/01/2000",
      email: "alice@example.com",
    })
  );

  await assertSucceeds(
    updateDoc(doc(alice, "users/alice"), {
      profilePictureUrl: "https://taivxyserowgwioffjob.supabase.co/storage/v1/object/public/profile-pictures/alice_123.jpg",
    })
  );
});

// ─────────────────────────────────────────────────────────────────────────────
// SECTION 7 — Phase 2: Chat Creation & Metadata Validation
// ─────────────────────────────────────────────────────────────────────────────

it("[DENY] user cannot create chat with more than 2 participants (blocks multi-party abuse)", async () => {
  const alice = testEnv.authenticatedContext("alice").firestore();

  await assertFails(
    setDoc(doc(alice, "chats/chat_multi"), {
      participants: ["alice", "bob", "mallory"],
      lastMessage: "",
      createdAt: 1000,
    })
  );
});

it("[DENY] user cannot create chat without being a participant", async () => {
  const alice = testEnv.authenticatedContext("alice").firestore();

  await assertFails(
    setDoc(doc(alice, "chats/chat_spoof"), {
      participants: ["bob", "charlie"],
      lastMessage: "",
      createdAt: 1000,
    })
  );
});

it("[DENY] user cannot create chat with duplicate self-participants", async () => {
  const alice = testEnv.authenticatedContext("alice").firestore();

  await assertFails(
    setDoc(doc(alice, "chats/chat_self"), {
      participants: ["alice", "alice"],
      lastMessage: "",
      createdAt: 1000,
    })
  );
});

it("[DENY] user cannot create chat with oversized lastMessage (> 500 chars)", async () => {
  const alice = testEnv.authenticatedContext("alice").firestore();

  await assertFails(
    setDoc(doc(alice, "chats/chat_large"), {
      participants: ["alice", "bob"],
      lastMessage: "m".repeat(501),
      createdAt: 1000,
    })
  );
});

it("[DENY] participant cannot update chat with oversized lastMessage (> 500 chars)", async () => {
  await seed("chats/chat_ok", {
    participants: ["alice", "bob"],
    lastMessage: "Initial",
    lastMessageTimestamp: 1000,
  });

  const alice = testEnv.authenticatedContext("alice").firestore();

  await assertFails(
    updateDoc(doc(alice, "chats/chat_ok"), {
      lastMessage: "m".repeat(501),
      lastMessageTimestamp: 2000,
    })
  );
});

it("[ALLOW] user can create legitimate 2-party chat with valid metadata", async () => {
  const alice = testEnv.authenticatedContext("alice").firestore();

  await assertSucceeds(
    setDoc(doc(alice, "chats/alice_bob"), {
      participants: ["alice", "bob"],
      participantNames: { alice: "Alice", bob: "Bob" },
      participantPhotos: { alice: "", bob: "" },
      lastMessage: "",
      lastMessageTimestamp: 1000,
      createdAt: 1000,
    })
  );
});

// ─────────────────────────────────────────────────────────────────────────────
// SECTION 8 — Phase 2: Message Payload Size & Schema Enforcement
// ─────────────────────────────────────────────────────────────────────────────

it("[DENY] participant cannot send message with oversized ciphertext (> 64KB)", async () => {
  await seed("chats/chat1", {
    participants: ["alice", "bob"],
    lastMessage: "",
  });

  const alice = testEnv.authenticatedContext("alice").firestore();

  await assertFails(
    setDoc(doc(alice, "chats/chat1/messages/msg_huge"), {
      senderId: "alice",
      ciphertext: "x".repeat(65537),
      messageType: 2,
      senderDeviceId: 1,
      status: "sent",
      timestampMillis: 1000,
    })
  );
});

it("[DENY] participant cannot spoof senderId (must match auth.uid)", async () => {
  await seed("chats/chat1", {
    participants: ["alice", "bob"],
    lastMessage: "",
  });

  const alice = testEnv.authenticatedContext("alice").firestore();

  // Alice trying to attribute message to Bob
  await assertFails(
    setDoc(doc(alice, "chats/chat1/messages/msg_spoof"), {
      senderId: "bob",
      ciphertext: "valid_ciphertext",
      messageType: 2,
      senderDeviceId: 1,
      status: "sent",
      timestampMillis: 1000,
    })
  );
});

it("[DENY] participant cannot send message with invalid initial status (e.g. 'read')", async () => {
  await seed("chats/chat1", {
    participants: ["alice", "bob"],
    lastMessage: "",
  });

  const alice = testEnv.authenticatedContext("alice").firestore();

  await assertFails(
    setDoc(doc(alice, "chats/chat1/messages/msg_status"), {
      senderId: "alice",
      ciphertext: "valid_ciphertext",
      messageType: 2,
      senderDeviceId: 1,
      status: "read", // Initial create must be 'sent'
      timestampMillis: 1000,
    })
  );
});

it("[DENY] participant cannot inject arbitrary unknown fields into message doc", async () => {
  await seed("chats/chat1", {
    participants: ["alice", "bob"],
    lastMessage: "",
  });

  const alice = testEnv.authenticatedContext("alice").firestore();

  await assertFails(
    setDoc(doc(alice, "chats/chat1/messages/msg_inject"), {
      senderId: "alice",
      ciphertext: "valid_ciphertext",
      messageType: 2,
      senderDeviceId: 1,
      status: "sent",
      timestampMillis: 1000,
      exploitField: "injected_data",
    })
  );
});

it("[ALLOW] participant can create legitimate E2EE message with valid ciphertext and timestamp", async () => {
  await seed("chats/chat1", {
    participants: ["alice", "bob"],
    lastMessage: "",
  });

  const alice = testEnv.authenticatedContext("alice").firestore();

  await assertSucceeds(
    setDoc(doc(alice, "chats/chat1/messages/msg_legit"), {
      senderId: "alice",
      ciphertext: "dGVzdF9jaXBoZXJ0ZXh0X2Jhc2U2NA==",
      messageType: 2,
      senderDeviceId: 1,
      text: null,
      imageUrl: null,
      status: "sent",
      timestampMillis: 1000,
    })
  );
});

it("[ALLOW] participant can create legitimate photo message with valid imageUrl and timestamp", async () => {
  await seed("chats/chat1", {
    participants: ["alice", "bob"],
    lastMessage: "",
  });

  const alice = testEnv.authenticatedContext("alice").firestore();

  await assertSucceeds(
    setDoc(doc(alice, "chats/chat1/messages/msg_photo"), {
      senderId: "alice",
      text: "",
      imageUrl: "https://taivxyserowgwioffjob.supabase.co/storage/v1/object/public/chat-images/chat1_123.jpg",
      status: "sent",
      timestampMillis: 1000,
    })
  );
});

// ─────────────────────────────────────────────────────────────────────────────
// SECTION 9 — Phase 2: Owner Prekey Bundle Bounds
// ─────────────────────────────────────────────────────────────────────────────

it("[DENY] owner cannot publish bundle with excessive one-time prekeys (> 250 keys, preventing document bloat)", async () => {
  const bob = testEnv.authenticatedContext("bob").firestore();

  const bloatMap = {};
  for (let i = 1; i <= 251; i++) {
    bloatMap[`key${i}`] = "base64_prekey_material";
  }

  await assertFails(
    setDoc(doc(bob, "users/bob/prekey_bundle/current"), {
      registrationId: 12345,
      deviceId: 1,
      identityKey: "bob-identity",
      signedPreKeyId: 1,
      signedPreKey: "bob-signed",
      signedPreKeySignature: "bob-sig",
      kyberPreKeyId: 1,
      kyberPreKey: "bob-kyber",
      kyberPreKeySignature: "bob-kyber-sig",
      oneTimePreKeys: bloatMap,
      updatedAt: 1000000,
    })
  );
});

it("[DENY] owner cannot inject unknown arbitrary fields into prekey bundle", async () => {
  const bob = testEnv.authenticatedContext("bob").firestore();

  await assertFails(
    setDoc(doc(bob, "users/bob/prekey_bundle/current"), {
      registrationId: 12345,
      deviceId: 1,
      identityKey: "bob-identity",
      signedPreKeyId: 1,
      signedPreKey: "bob-signed",
      signedPreKeySignature: "bob-sig",
      kyberPreKeyId: 1,
      kyberPreKey: "bob-kyber",
      kyberPreKeySignature: "bob-kyber-sig",
      oneTimePreKeys: { key1: "val1" },
      updatedAt: 1000000,
      unknownBackdoorField: "injected",
    })
  );
});
