package app.alhaderech.run;

import android.os.CancellationSignal;

import androidx.core.content.ContextCompat;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialException;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import java.util.concurrent.Executor;

/**
 * Google sign-in through Android's Credential Manager (Google does not allow it inside a WebView).
 * Returns a Google ID token; the web app signs in to Firebase with it.
 */
@CapacitorPlugin(name = "GoogleAuth")
public class GoogleAuthPlugin extends Plugin {

    @PluginMethod
    public void signIn(PluginCall call) {
        String clientId = call.getString("clientId");
        if (clientId == null || clientId.isEmpty()) { call.reject("missing clientId"); return; }
        GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(clientId).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder().addCredentialOption(option).build();
        CredentialManager cm = CredentialManager.create(getContext());
        Executor exec = ContextCompat.getMainExecutor(getContext());
        cm.getCredentialAsync(getActivity(), request, new CancellationSignal(), exec,
            new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                @Override
                public void onResult(GetCredentialResponse response) {
                    Credential c = response.getCredential();
                    if (c instanceof CustomCredential && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(c.getType())) {
                        try {
                            GoogleIdTokenCredential g = GoogleIdTokenCredential.createFrom(((CustomCredential) c).getData());
                            JSObject r = new JSObject();
                            r.put("idToken", g.getIdToken());
                            r.put("email", g.getId());
                            r.put("name", g.getDisplayName());
                            call.resolve(r);
                        } catch (Exception e) {
                            call.reject("bad credential: " + e.getMessage());
                        }
                    } else {
                        call.reject("unexpected credential");
                    }
                }

                @Override
                public void onError(GetCredentialException e) {
                    call.reject(String.valueOf(e.getMessage()), e.getType());
                }
            });
    }

    @PluginMethod
    public void signOut(PluginCall call) {
        CredentialManager cm = CredentialManager.create(getContext());
        cm.clearCredentialStateAsync(new ClearCredentialStateRequest(), new CancellationSignal(), ContextCompat.getMainExecutor(getContext()),
            new CredentialManagerCallback<Void, ClearCredentialException>() {
                @Override
                public void onResult(Void unused) { call.resolve(); }

                @Override
                public void onError(ClearCredentialException e) { call.resolve(); }
            });
    }
}
