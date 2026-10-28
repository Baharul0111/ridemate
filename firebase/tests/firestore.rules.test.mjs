// Emulator tests for ../firestore.rules (architecture §4.18 A2).
// Run: cd firebase/tests && npm ci && npm test   (starts the Firestore emulator via `firebase emulators:exec`)
import { after, before, beforeEach, describe, test } from "node:test";
import { readFileSync } from "node:fs";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from "@firebase/rules-unit-testing";
import {
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  query,
  setDoc,
  setLogLevel,
  updateDoc,
  where,
} from "firebase/firestore";

const PROJECT_ID = "demo-ridemate";
setLogLevel("error"); // denied writes are expected here; the SDK would log each one as a warning
const SERVER_ONLY = [
  "events/e1",
  "audit/a1",
  "usage_snapshot/h1",
  "spend_shards/lifetime_s1",
  "spend_shards/rollup",
  "blocked_uids/list",
  "metrics/2026-10-28",
  "metrics/2026-10-28/shards/202610281000",
  "ondc_txn/t-alice/private/x",
  "anything/else",
];

let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules: readFileSync(new URL("../firestore.rules", import.meta.url), "utf8") },
  });
});

after(async () => {
  await env?.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, "ondc_txn/t-alice"), { uid: "alice", state: "CONFIRMED", demo: true });
    await setDoc(doc(db, "ondc_txn/t-bob"), { uid: "bob", state: "SEARCHING_NET", demo: true });
    await setDoc(doc(db, "ondc_event/e-alice"), { uid: "alice", txnId: "t-alice", action: "on_confirm" });
    await setDoc(doc(db, "ondc_event/e-no-owner"), { txnId: "t-alice", action: "on_status" });
    for (const path of SERVER_ONLY) {
      await setDoc(doc(db, path), { uid: "alice", note: "server data" });
    }
  });
});

const alice = () => env.authenticatedContext("alice", { firebase: { sign_in_provider: "google.com" } }).firestore();
const bob = () => env.authenticatedContext("bob", { firebase: { sign_in_provider: "google.com" } }).firestore();
const anonymousAlice = () =>
  env.authenticatedContext("alice", { firebase: { sign_in_provider: "anonymous" } }).firestore();
const nobody = () => env.unauthenticatedContext().firestore();

describe("ondc_txn: owner-only reads", () => {
  test("the owner can read their transaction", async () => {
    await assertSucceeds(getDoc(doc(alice(), "ondc_txn/t-alice")));
  });

  test("another signed-in user cannot", async () => {
    await assertFails(getDoc(doc(bob(), "ondc_txn/t-alice")));
  });

  test("a signed-out caller cannot", async () => {
    await assertFails(getDoc(doc(nobody(), "ondc_txn/t-alice")));
  });

  test("a missing document is not readable", async () => {
    await assertFails(getDoc(doc(alice(), "ondc_txn/t-missing")));
  });

  test("listing works only when filtered to the caller's own uid", async () => {
    const txns = collection(alice(), "ondc_txn");
    await assertSucceeds(getDocs(query(txns, where("uid", "==", "alice"))));
    await assertFails(getDocs(query(txns, where("uid", "==", "bob"))));
    await assertFails(getDocs(txns));
  });

  test("clients can never write, not even to their own transaction", async () => {
    const db = alice();
    await assertFails(setDoc(doc(db, "ondc_txn/t-new"), { uid: "alice", state: "CONFIRMED" }));
    await assertFails(updateDoc(doc(db, "ondc_txn/t-alice"), { state: "CANCEL_CONFIRMED" }));
    await assertFails(setDoc(doc(db, "ondc_txn/t-alice"), { uid: "alice" }, { merge: true }));
    await assertFails(deleteDoc(doc(db, "ondc_txn/t-alice")));
  });
});

describe("ondc_event: owner-only reads", () => {
  test("the owner can read a record that carries their uid", async () => {
    await assertSucceeds(getDoc(doc(alice(), "ondc_event/e-alice")));
  });

  test("records of other users, or without a uid, are not readable", async () => {
    await assertFails(getDoc(doc(bob(), "ondc_event/e-alice")));
    await assertFails(getDoc(doc(alice(), "ondc_event/e-no-owner")));
    await assertFails(getDoc(doc(nobody(), "ondc_event/e-alice")));
  });

  test("clients can never write", async () => {
    const db = alice();
    await assertFails(setDoc(doc(db, "ondc_event/e-new"), { uid: "alice", action: "on_cancel" }));
    await assertFails(deleteDoc(doc(db, "ondc_event/e-alice")));
  });
});

describe("everything else is server-only", () => {
  for (const path of SERVER_ONLY) {
    test(`${path}: no client reads or writes`, async () => {
      for (const db of [alice(), anonymousAlice(), bob(), nobody()]) {
        await assertFails(getDoc(doc(db, path)));
        await assertFails(setDoc(doc(db, path), { uid: "alice" }));
        await assertFails(deleteDoc(doc(db, path)));
      }
    });
  }

  test("collections cannot be listed", async () => {
    for (const name of ["events", "audit", "usage_snapshot", "spend_shards", "metrics", "blocked_uids"]) {
      await assertFails(getDocs(query(collection(alice(), name), where("uid", "==", "alice"))));
    }
  });
});
