# android-subs-tracking
## How to use
### Initialization
Call at application starting time, after Firebase/Amplitude initialization.

`PurchaseTrackingHelper.init(Context context, String firebaseRemoteUrl, String expCohortString, String revenueCatApiKey)`

### Tracking
Call in the `onPurchasesUpdated` callback of billing client.

`PurchaseTrackingHelper.init(Context context, String firebaseRemoteUrl, String expCohortString, String revenueCatApiKey)`



