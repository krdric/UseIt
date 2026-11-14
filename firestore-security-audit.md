# Firestore security analysis

## Current code scan

The Kotlin source currently contains no Firestore SDK references, collection paths, or Firestore queries. The existing app has email/password Firebase Authentication and in-memory rental listings, saved item IDs, and booking names.

## Planned Firestore model

### `users/{uid}` (private)
- `displayName`: string, required, 1–80 characters.
- `email`: string, required, 3–254 characters; private.
- `createdAt`: timestamp, required, immutable.
- `updatedAt`: timestamp, required.
- No client-writable role or admin field. Admin access is based on the verified project-owner email in Security Rules.

### `listings/{listingId}`
- `ownerUid`: string, required, immutable, must equal the authenticated UID on create.
- `ownerName`: string, required, 1–80 characters; public display label, no email/phone.
- `title`: string, required, 1–80 characters.
- `category`: string, required, 1–32 characters.
- `emoji`: string, required, 1–8 characters.
- `dailyPrice`: integer, required, 1–200000 INR.
- `area`: string, required, 1–80 characters.
- `description`: string, required, 1–1200 characters.
- `status`: string, required; `active` or `paused`.
- `createdAt`: timestamp, required, immutable.
- `updatedAt`: timestamp, required.

### `bookings/{bookingId}`
- `listingId`: string, required.
- `renterUid`, `ownerUid`: strings, required and immutable.
- `itemTitle`: string, required, 1–80 characters.
- `dailyPrice`: integer, required, 1–200000 INR.
- `status`: string, required; `requested`, `accepted`, `declined`, `cancelled`, or `completed`.
- `createdAt`: timestamp, required, immutable.
- `updatedAt`: timestamp, required.

### `users/{uid}/saved/{listingId}`
- `listingId`: string, required, equal to the document ID.
- `createdAt`: timestamp, required.

## Planned access and queries

- Authenticated discovery query: `listings.whereEqualTo("status", "active")`.
- Owner inventory query: `listings.whereEqualTo("ownerUid", uid)`.
- Saved items query: `users/{uid}/saved`; only that user or admin.
- Renter booking query: `bookings.whereEqualTo("renterUid", uid)`.
- Owner request query: `bookings.whereEqualTo("ownerUid", uid)`.
- User profile: direct read/write to `users/{uid}` only by that UID; admins may read for moderation.
- Listing reads: signed-in users may read active listings; owners read their own paused listings; admins may read all.
- Booking reads: renter, listing owner, or admin.
- Booking writes: verified renter may create a request for an active listing. Renter and owner may only perform allowed status transitions.
- Admin identity: verified `rishikardam470@gmail.com`; role is never read from client-writable profile data.

## Data operations

- Create profile on signup; update profile name only.
- Create listings; owner may edit own listing details/status; no client hard-delete.
- Create booking request; renter cancels; listing owner accepts/declines/completes.
- Create/delete saved-item records.
- Firestore client queries use equality filters only; no orderBy, range, or array queries planned.
