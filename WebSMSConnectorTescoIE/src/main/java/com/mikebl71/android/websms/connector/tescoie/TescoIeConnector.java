package com.mikebl71.android.websms.connector.tescoie;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.text.TextUtils;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.util.ArrayList;

import de.ub0r.android.websms.connector.common.BasicSMSLengthCalculator;
import de.ub0r.android.websms.connector.common.Connector;
import de.ub0r.android.websms.connector.common.ConnectorCommand;
import de.ub0r.android.websms.connector.common.ConnectorSpec;
import de.ub0r.android.websms.connector.common.ConnectorSpec.SubConnectorSpec;
import de.ub0r.android.websms.connector.common.Log;
import de.ub0r.android.websms.connector.common.Utils;
import de.ub0r.android.websms.connector.common.WebSMSException;
import de.ub0r.android.websms.connector.common.WebSMSNoNetworkException;

/**
 * Main class for TescoIE Connector.
 * Receives commands from WebSMS and acts upon them.
 */
public class TescoIeConnector extends Connector {

    // Logging tag
    private static final String TAG = "tescoie";

    private static final String ENCODING = "UTF-8";
    private static final String USER_AGENT = "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:36.0) Gecko/20100101 Firefox/36.0";

    private static final int MAX_TEXT_LEN = 160;

    private static class Balance {
        public int nationalSms;
        public int internationalSms;
    }

    private static class BalanceHolder {
        public Balance balance;
    }

        /**
     * Initializes {@link ConnectorSpec}. This is only run once.
     * Changing properties are set in updateSpec().
     */
    @Override
    public ConnectorSpec initSpec(Context context) {
        ConnectorSpec c = new ConnectorSpec(context.getString(R.string.connector_tescoie_name));
        c.setAuthor(context.getString(R.string.connector_tescoie_author));
        c.setBalance(null);
        c.setSMSLengthCalculator(new BasicSMSLengthCalculator(new int[]{ MAX_TEXT_LEN }));
        c.setCapabilities(ConnectorSpec.CAPABILITIES_PREFS
                | ConnectorSpec.CAPABILITIES_SEND
                | ConnectorSpec.CAPABILITIES_UPDATE);

        c.addSubConnector(TAG, c.getName(), SubConnectorSpec.FEATURE_MULTIRECIPIENTS);

        return c;
    }

    /**
     * Updates connector's status.
     */
    @Override
    public ConnectorSpec updateSpec(Context context, ConnectorSpec connectorSpec) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.getBoolean(Preferences.PREFS_ENABLED, false)) {
            connectorSpec.setReady();
        } else {
            connectorSpec.setStatus(ConnectorSpec.STATUS_INACTIVE);
        }
        return connectorSpec;
    }

    /**
     * Called to update balance.
     */
    @Override
    protected void doUpdate(final Context context, final Intent intent) throws IOException {
        if (!Utils.isNetworkAvailable(context)) {
            throw new WebSMSNoNetworkException(context);
        }

        login(context);

        Balance balance = getBalance(context);
        getSpec(context).setBalance(balanceToString(balance));
    }

    /**
     * Called to send the actual message.
     */
    @Override
    protected void doSend(Context context, Intent intent) throws IOException {
        if (!Utils.isNetworkAvailable(context)) {
            throw new WebSMSNoNetworkException(context);
        }

        ConnectorCommand command = new ConnectorCommand(intent);
        String[] recipients = command.getRecipients();

        login(context);

        String remainingText = command.getText();
        BalanceHolder balanceHolder = new BalanceHolder();

        while (!TextUtils.isEmpty(remainingText)) {
            String curText = remainingText.length() > MAX_TEXT_LEN ? remainingText.substring(0, MAX_TEXT_LEN) : remainingText;
            remainingText = remainingText.length() > MAX_TEXT_LEN ? remainingText.substring(MAX_TEXT_LEN) : "";

            sendWebText(context, recipients, curText, balanceHolder);
            getSpec(context).setBalance(balanceToString(balanceHolder.balance));
        }
    }


    private void login(Context context) throws IOException {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        // to enable network trace:
        //java.util.logging.Logger.getLogger("org.apache.http.wire").setLevel(java.util.logging.Level.FINEST);
        // adb shell setprop log.tag.org.apache.http.wire VERBOSE

        Utils.HttpOptions options = new Utils.HttpOptions(ENCODING);
        options.trustAll = true;
        options.userAgent = USER_AGENT;

        options.url = "https://my.tescomobile.ie/tmi-selfcare-web/j_spring_security_check";

        ArrayList<BasicNameValuePair> d = new ArrayList<BasicNameValuePair>();
        addParam(d, "j_username", prefs.getString(Preferences.PREFS_USERNAME, ""));
        addParam(d, "j_password", prefs.getString(Preferences.PREFS_PASSWORD, ""));
        options.addFormParameter(d);

        options.referer = "https://my.tescomobile.ie/tmi-selfcare-web/login";

        HttpResponse httpResponse = Utils.getHttpClient(options);

        String response = responseToString(context, httpResponse);

        if (!response.contains("Logout")) {
            throw new WebSMSException(context, R.string.error_login);
        }
    }

    private Balance getBalance(Context context) throws IOException {
        Utils.HttpOptions options = new Utils.HttpOptions(ENCODING);
        options.trustAll = true;
        options.userAgent = USER_AGENT;
        addHeader(options, "X-Requested-With", "XMLHttpRequest");

        options.url = "https://my.tescomobile.ie/tmi-selfcare-web/rest/customer/balance";

        options.referer = "https://my.tescomobile.ie/tmi-selfcare-web/";

        HttpResponse httpResponse = Utils.getHttpClient(options);

        String response = responseToString(context, httpResponse);

        Balance balance;
        try {
            JSONObject json = new JSONObject(response);

            JSONObject webTextBalance = json.optJSONObject("webTextBalance");
            balance = jsonToBalance(webTextBalance);

        } catch (JSONException ex) {
            Log.d(TAG, "Unexpected balance response: " + response);
            throw new WebSMSException(context, R.string.error_unexpected_resp);
        }
        return balance;
    }

    private void sendWebText(Context context, String[] recipients, String text, BalanceHolder balanceHolder) throws IOException {
        Utils.HttpOptions options = new Utils.HttpOptions(ENCODING);
        options.trustAll = true;
        options.userAgent = USER_AGENT;
        addHeader(options, "X-Requested-With", "XMLHttpRequest");

        options.url = "https://my.tescomobile.ie/tmi-selfcare-web/rest/webtext/sendWebText";

        try {
            JSONObject json = new JSONObject();
            json.put("message", text);

            json.put("contacts", new JSONArray());
            json.put("groups", new JSONArray());

            JSONArray recArr = new JSONArray();
            for (String recipient : recipients) {
                recArr.put(Utils.getRecipientsNumber(recipient));
            }
            json.put("msisdns", recArr);
            addJson(options, json);

        } catch (JSONException ex) {
            throw new WebSMSException(context, R.string.error_prepare_req);
        }

        options.referer = "https://my.tescomobile.ie/tmi-selfcare-web/webtext";

        HttpResponse httpResponse = Utils.getHttpClient(options);

        String response = responseToString(context, httpResponse);

        try {
            JSONObject json = new JSONObject(response);

            if (!TextUtils.isEmpty(json.optString("error"))) {
                throw new WebSMSException(context, R.string.error_send, json.getString("error"));
            }

            // unfortunately, the balances returned by the server are old balances,
            // it takes several seconds for balances to get updated,
            // so try to guess the new balance

            if (balanceHolder.balance == null) {
                balanceHolder.balance = jsonToBalance(json);
            }

            for (String recipient : recipients) {
                String phNum = Utils.getRecipientsNumber(recipient);

                if (phNum.startsWith("00") && !phNum.startsWith("00353")
                    || phNum.startsWith("+") && !phNum.startsWith("+353")) {
                    // assume international message
                    if (balanceHolder.balance.internationalSms > 0) {
                        --balanceHolder.balance.internationalSms;
                    }
                } else {
                    // assume national message
                    if (balanceHolder.balance.nationalSms > 0) {
                        --balanceHolder.balance.nationalSms;
                    }
                }
            }

        } catch (JSONException ex) {
            Log.d(TAG, "Unexpected send response: " + response);
            throw new WebSMSException(context, R.string.error_unexpected_resp);
        }
    }


    private ArrayList<BasicNameValuePair> addParam(ArrayList<BasicNameValuePair> d, String n, String v) {
        if (!TextUtils.isEmpty(n)) {
            d.add(new BasicNameValuePair(n, v));
        }
        return d;
    }

    private void addHeader(Utils.HttpOptions options, String name, String value) {
        if (options.headers == null) {
            options.headers = new ArrayList<Header>();
        }
        options.headers.add(new BasicHeader(name, value));
    }

    private void addJson(Utils.HttpOptions options, JSONObject json) throws UnsupportedEncodingException {
        StringEntity se = new StringEntity(json.toString(), options.encoding);
        se.setContentType("application/json");
        options.postData = se;
    }

    private String responseToString(Context context, HttpResponse response) throws IOException {
        if (response.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_OK) {
            throw new WebSMSException(context,
                    R.string.error_http,
                    response.getStatusLine().getReasonPhrase());
        }

        if (response.getEntity() == null) {
            throw new WebSMSException(context, R.string.error_empty_response);
        }

        return Utils.stream2str(response.getEntity().getContent()).trim();
    }

    private Balance jsonToBalance(JSONObject jsonBalance) {
        if (jsonBalance == null) {
            return null;
        }
        Balance balance = new Balance();
        balance.nationalSms = jsonBalance.optInt("nationalSms", 0);
        if (balance.nationalSms < 0) {
            balance.nationalSms = 0;
        }
        balance.internationalSms = jsonBalance.optInt("internationalSms", 0);
        if (balance.internationalSms < 0) {
            balance.internationalSms = 0;
        }
        return balance;
    }

    private String balanceToString(Balance balance) {
        if (balance == null) {
            return "";
        }
        return "" + balance.nationalSms + "+" + balance.internationalSms;
    }

}

