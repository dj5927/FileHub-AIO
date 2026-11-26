# Premium plans v0.1.142 / v1.0.27
- Play annual auto-renewing subscription: product filehub_premium_annual, base plan annual, P1Y, KRW 2,900 per year; no introductory offer or installments in this UI.
- Play one-time non-consumable product: existing filehub_full_version, one permanent Buy option, KRW 9,900. Existing verified permanent purchases remain valid.
- Play Console controls actual localized price. If no product details, planned Korean price is shown and checkout disabled.
- Configure/activate both products, pricing, account licensing key and license testers in Play Console. Internal build has no live billing credentials. Test purchase, renewal, cancellation, expiration, restore and pending purchases before production. No subscription entitlement is persisted as a lifetime receipt. The current client uses Play ownership queries and local signature verification; production should implement backend verification and real-time developer notifications for robust subscription lifecycle.
- Direct edition remains unlocked and has no billing SDK or upgrade page.
