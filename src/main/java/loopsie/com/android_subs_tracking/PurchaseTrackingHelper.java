package loopsie.com.android_subs_tracking;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import com.amplitude.api.Amplitude;
import com.amplitude.api.Revenue;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.SkuDetails;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.gms.ads.identifier.AdvertisingIdClient;
import com.google.android.gms.common.GooglePlayServicesNotAvailableException;
import com.google.android.gms.common.GooglePlayServicesRepairableException;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.revenuecat.purchases.PurchaserInfo;
import com.revenuecat.purchases.Purchases;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;


public class PurchaseTrackingHelper {


    private static final String REVENUE_TEST = "revenue_test";
    private static RequestQueue mRequestQueue;
    private static Purchases revenueCat;
    private final String firebaseRemoteUrl;
    private final String expCohortString;
    private static PurchaseTrackingHelper purchaseTrackingHelper = null;
    private Context context;



    private PurchaseTrackingHelper(Context context, String firebaseRemoteUrl, String expCohortString){
        this.context = context;
        this.firebaseRemoteUrl = firebaseRemoteUrl;
        this.expCohortString = expCohortString;
    }

    /**
     * Track a revenue (use in onPurchasesUpdated callback)
     * @param context Android context
     * @param skuDetails SkuDetail of the launched sky
     * @param lastPurchase Purchase object (last bought)
     * @param purchases List of purchases returned by android onPurchasesUpdated
     */
    public void trackRevenue(Context context, final SkuDetails skuDetails, final Purchase lastPurchase, List<Purchase> purchases) {
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("receipt", lastPurchase.getOriginalJson());
        } catch (JSONException e) {
            e.printStackTrace();
        }
        Amplitude.getInstance().logEvent(REVENUE_TEST,jsonObject);
        AdvIdRetriever advIdRetriever = new AdvIdRetriever(context,skuDetails,lastPurchase);
        advIdRetriever.execute();
        revenueCat.onPurchasesUpdated(purchases);

 }



    private void trackRevenueV2(Context context, final SkuDetails skuDetails, final Purchase lastPurchase, String advId){


        double doublePrice = (double)(skuDetails.getPriceAmountMicros())/1000000d;
        String uniqueID = FirebaseInstanceId.getInstance().getId();
        JSONObject jsonObject = null;
        try {
            jsonObject = new JSONObject();
            JSONObject jsonPurchase = new JSONObject();
            jsonObject.put("purchase",jsonPurchase);
            jsonPurchase.put("price",doublePrice);
            jsonPurchase.put("userId",uniqueID);
            jsonPurchase.put("productId",lastPurchase.getSku());
            jsonPurchase.put("eventTimestampMs",System.currentTimeMillis());
            jsonPurchase.put("quantity",1);
            jsonPurchase.put("receipt",lastPurchase.getOriginalJson());
            jsonPurchase.put("receiptSig",lastPurchase.getSignature());
            jsonPurchase.put("freeTrial",!skuDetails.getFreeTrialPeriod().equals(""));
            jsonPurchase.put("packageName",context.getPackageName());
            jsonPurchase.put("currencyCode",skuDetails.getPriceCurrencyCode());
            jsonPurchase.put("os","ANDROID");
            jsonPurchase.put("environment",(BuildConfig.DEBUG) ? "SANDBOX" : "PRODUCTION");
            jsonPurchase.put("advertiserId",advId);
            jsonPurchase.put("extInfo",buildExtInfo());
        } catch (JSONException e) {
            e.printStackTrace();
        }
        JsonObjectRequest strReq = new JsonObjectRequest(Request.Method.POST, firebaseRemoteUrl, jsonObject, new Response.Listener<JSONObject>() {
            @Override
            public void onResponse(JSONObject response) {

            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                logEmptyRevenue(lastPurchase);
            }
        });

        strReq.setRetryPolicy(new DefaultRetryPolicy(
                5000,
                5,
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
        getRequestQueue().add(strReq);

    }


    private void logEmptyRevenue(Purchase lastPurchase) {
        Revenue revenue = new Revenue();
        revenue.setProductId(lastPurchase.getSku());
        revenue.setReceipt(lastPurchase.getOrderId(), lastPurchase.getSignature());
        revenue.setPrice(0);
        revenue.setQuantity(1);
        Map<String, Object> map = new HashMap<>();
        map.put(expCohortString, FirebaseRemoteConfig.getInstance().getString(expCohortString));
        revenue.setEventProperties(new JSONObject(map));
        Amplitude.getInstance().logRevenueV2(revenue);
    }



    private JSONArray buildExtInfo(){
        JSONArray extInfo = new JSONArray();
        extInfo.put("a2"); // default for Android

        String packageName = context.getPackageName();
        extInfo.put(packageName);

        PackageInfo pi = null;
        try {
            pi = context.getPackageManager().getPackageInfo(packageName, 0);
            int versionCode = pi.versionCode;
            String versionName = pi.versionName;
            extInfo.put(versionCode);
            extInfo.put(versionName);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        extInfo.put(Build.VERSION.RELEASE);
        extInfo.put(Build.MODEL);
        extInfo.put(getLocale());

        TimeZone tz = TimeZone.getDefault();
        String deviceTimezoneAbbreviation = tz.getDisplayName(tz.inDaylightTime(new Date()), TimeZone.SHORT);
        String deviceTimeZoneName = tz.getID();
        extInfo.put(deviceTimezoneAbbreviation);

        extInfo.put("NoCarrier");

        int width = 0;
        int height = 0;
        double density = 0;
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                Display display = wm.getDefaultDisplay();
                DisplayMetrics displayMetrics = new DisplayMetrics();
                display.getMetrics(displayMetrics);
                width = displayMetrics.widthPixels;
                height = displayMetrics.heightPixels;
                density = displayMetrics.density;
            }
        } catch (Exception e) {
            // Swallow
        }
        String densityStr = String.format("%.2f", density);

        extInfo.put(width);
        extInfo.put(height);
        extInfo.put(densityStr);
        int numCPUCores = Math.max(Runtime.getRuntime().availableProcessors(), 1);
        extInfo.put(numCPUCores);
        extInfo.put(32);
        extInfo.put(6);

        extInfo.put(deviceTimeZoneName);

        return extInfo;


    }


    private String getLocale(){
        Locale locale;
        try {
            locale = context.getResources().getConfiguration().locale;
        } catch (Exception e) {
            locale = Locale.getDefault();
        }
        return locale.getLanguage() + "_" + locale.getCountry();
    }


    public RequestQueue getRequestQueue() {
        if (mRequestQueue == null) {
            // getApplicationContext() is key, it keeps you from leaking the
            // Activity or BroadcastReceiver if someone passes one in.
            mRequestQueue = Volley.newRequestQueue(context.getApplicationContext());
        }

        return mRequestQueue;
    }




    private class AdvIdRetriever extends AsyncTask<Void, Void, Void> {


        private final Context context;
        private final Purchase lastPurchase;
        private final SkuDetails skuDetails;
        private boolean hasError = false;
        private AdvertisingIdClient.Info info;

        public AdvIdRetriever(Context context,final SkuDetails skuDetails, final Purchase lastPurchase){
            this.context = context.getApplicationContext();
            this.skuDetails = skuDetails;
            this.lastPurchase = lastPurchase;
        }



        @Override
        protected Void doInBackground(Void... params) {
            try {
                info = AdvertisingIdClient.getAdvertisingIdInfo(context);
            } catch (IOException | GooglePlayServicesNotAvailableException | GooglePlayServicesRepairableException e) {
                e.printStackTrace();
                hasError = true;
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            String advertiserId;
            if(hasError){
                advertiserId = "1111-1111-1111-1111";
            }else{
                advertiserId = info.getId();
            }
            trackRevenueV2(context,skuDetails,lastPurchase,advertiserId);
        }

        @Override
        protected void onPreExecute() {}

        @Override
        protected void onProgressUpdate(Void... values) {}
    }

    /**
     *
     * @param context Android context
     * @param firebaseRemoteUrl Firebase remote url for purchase tracking
     * @param expCohortString String of the parameter used to do cohort
     */
    public static void init(Context context, String firebaseRemoteUrl, String expCohortString, String revenueCatApiKey) {
        purchaseTrackingHelper = new PurchaseTrackingHelper(context.getApplicationContext(), firebaseRemoteUrl, expCohortString);

        String userId = FirebaseInstanceId.getInstance().getId();
        revenueCat = new Purchases.Builder(context,revenueCatApiKey).appUserID(userId).build();
        revenueCat.setListener(new Purchases.PurchasesListener() {
            @Override
            public void onCompletedPurchase(String s, PurchaserInfo purchaserInfo) {

            }

            @Override
            public void onFailedPurchase(Purchases.ErrorDomains errorDomains, int i, String s) {

            }

            @Override
            public void onReceiveUpdatedPurchaserInfo(PurchaserInfo purchaserInfo) {

            }

            @Override
            public void onRestoreTransactions(PurchaserInfo purchaserInfo) {

            }

            @Override
            public void onRestoreTransactionsFailed(Purchases.ErrorDomains errorDomains, int i, String s) {

            }
        });
    }

    public static PurchaseTrackingHelper getInstance(){
        return  purchaseTrackingHelper;
    }




}
