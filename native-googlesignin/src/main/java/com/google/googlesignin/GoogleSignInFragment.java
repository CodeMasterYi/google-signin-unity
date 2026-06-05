/*
 * Copyright 2017 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.googlesignin;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;
import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInOptionsExtension;
import com.google.android.gms.auth.api.signin.GoogleSignInResult;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.Api;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Scope;
import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Activity fragment with no UI added to the parent activity in order to manage the accessing of the
 * player's email address and tokens.
 */
public class GoogleSignInFragment extends Fragment
    implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

  // Tag uniquely identifying this fragment.
  public static final String FRAGMENT_TAG = "signin.SignInFragment";
  private static final int RC_SIGNIN = 9009;

  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final Executor mainExecutor =
      new Executor() {
        @Override
        public void execute(Runnable command) {
          mainHandler.post(command);
        }
      };

  /**
   * Handle the Google API Client connection being connected.
   *
   * @param connectionHint - is not used.
   */
  @Override
  public void onConnected(@Nullable Bundle connectionHint) {
    GoogleSignInHelper.logDebug("onConnected!");
    if (request == null) {
      GoogleSignInHelper.logError("Connected with no pending request.");
      return;
    }

    if (mGoogleApiClient.hasConnectedApi(Auth.GOOGLE_SIGN_IN_API)) {
      GoogleSignInHelper.logDebug("has connected auth!");
      legacySilentSignIn();
    } else {
      Intent signInIntent = Auth.GoogleSignInApi.getSignInIntent(mGoogleApiClient);
      startActivityForResult(signInIntent, RC_SIGNIN);
    }
  }

  @Override
  public void onConnectionSuspended(int cause) {
    GoogleSignInHelper.logDebug("onConnectionSuspended() called: " + cause);
  }

  @Override
  public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
    GoogleSignInHelper.logError("Connection failed: " + connectionResult.getErrorCode());
    if (connectionResult.hasResolution()) {
      Intent signInIntent = Auth.GoogleSignInApi.getSignInIntent(mGoogleApiClient);
      startActivityForResult(signInIntent, RC_SIGNIN);
    } else {
      finishRequest(connectionResult.getErrorCode(), null);
    }
  }

  public void disconnect() {
    clearRequest(true);
    clearCredentialManagerState();

    if (mGoogleApiClient != null) {
      try {
        Auth.GoogleSignInApi.revokeAccess(mGoogleApiClient);
      } catch (Throwable throwable) {
        GoogleSignInHelper.logError("Failed to revoke legacy access: " + throwable.getMessage());
      }
      mGoogleApiClient.disconnect();
    }
  }

  /**
   * The state of the fragment. It can only handle one sign-in request at a time, so we use these
   * values to keep track of the request lifecycle.
   */
  private enum State {
    NEW,
    READY,
    PENDING,
    PENDING_SILENT,
    BUSY
  }

  private State state;

  /**
   * The request to sign-in. This contains the configuration for the API client/Sign-in options and
   * the callback information used to communicate the result.
   */
  private TokenRequest request = null;

  private GoogleApiClient mGoogleApiClient;
  private CredentialManager credentialManager;

  // TODO: make config async.
  private static GoogleSignInFragment theFragment;

  /**
   * Gets the instance of the fragment.
   *
   * @param parentActivity - the activity to attach the fragment to.
   * @return the instance.
   */
  public static GoogleSignInFragment getInstance(Activity parentActivity) {
    GoogleSignInFragment fragment =
        (GoogleSignInFragment) parentActivity.getFragmentManager().findFragmentByTag(FRAGMENT_TAG);

    fragment = (fragment != null) ? fragment : theFragment;
    if (fragment == null) {
      GoogleSignInHelper.logDebug("Creating fragment");
      fragment = new GoogleSignInFragment();
      FragmentTransaction trans = parentActivity.getFragmentManager().beginTransaction();
      trans.add(fragment, FRAGMENT_TAG);
      trans.commitAllowingStateLoss();
      theFragment = fragment;
    }
    return fragment;
  }

  public synchronized boolean submitRequest(TokenRequest request) {
    if (this.request == null || this.state == State.READY) {
      this.request = request;
      return true;
    }
    GoogleSignInHelper.logError(
        String.format(
            Locale.getDefault(),
            "Existing request: %s ignoring %s.  State = %s",
            this.request,
            request,
            this.state));
    return false;
  }

  private synchronized State getState() {
    return state;
  }

  private synchronized void setState(State state) {
    this.state = state;
  }

  /**
   * Signs out and disconnects the client. NOTE: if you are using the Games API, you **MUST** call
   * Games.signout() before this method. Failure to do so will result in not being able to access
   * Games API until the application is restarted.
   */
  public void signOut() {
    clearRequest(true);
    clearCredentialManagerState();

    if (mGoogleApiClient != null) {
      try {
        Auth.GoogleSignInApi.signOut(mGoogleApiClient);
      } catch (Throwable throwable) {
        GoogleSignInHelper.logError("Failed to sign out legacy client: " + throwable.getMessage());
      }
    }
  }

  /**
   * Starts the sign-in process using the Sign-in UI, if any UI is needed. This is in contrast to
   * startSignInSilently, which does not use any UI.
   *
   * @return true if the sign-in flow was started.
   */
  public boolean startSignIn() {
    if (request == null) {
      GoogleSignInHelper.logError("Request not configured! Failing authenticate");
      return false;
    }
    if (getState() == State.BUSY) {
      GoogleSignInHelper.logError("There is already a pending callback configured.");
    } else if (getState() == State.READY) {
      processRequest(false);
    } else {
      processWhenReady(false);
    }
    return true;
  }

  /**
   * Starts the sign-in silently flow.
   *
   * @return true if the flow was started successfully.
   */
  public boolean startSignInSilently() {
    if (request == null) {
      GoogleSignInHelper.logError("Request not configured! Failing authenticate");
      return false;
    }
    if (getState() == State.BUSY) {
      GoogleSignInHelper.logError("There is already a pending callback configured.");
    } else if (getState() == State.READY) {
      processRequest(true);
    } else {
      processWhenReady(true);
    }
    return true;
  }

  /**
   * Indicates that the token request has been set and it is ready to be processed. The processing
   * can start once the fragment is attached to the activity and initialized.
   *
   * @param silent - true if the sign-in should be silent.
   */
  private void processWhenReady(boolean silent) {
    GoogleSignInHelper.logInfo("Fragment not initialized yet, waiting to authenticate");
    setState(silent ? State.PENDING_SILENT : State.PENDING);
  }

  /**
   * Processes the token requests that are queued up. Credential Manager handles simple Google ID
   * token sign-ins; the legacy flow handles auth codes, extra scopes, Games, and compatibility
   * fallback.
   */
  private void processRequest(final boolean silent) {
    try {
      if (request != null) {
        setState(State.BUSY);
      } else {
        GoogleSignInHelper.logInfo("No pending configuration, returning");
        return;
      }

      request
          .getPendingResponse()
          .setResultCallback(
              new ResultCallback<TokenResult>() {
                @Override
                public void onResult(@NonNull TokenResult tokenResult) {
                  GoogleSignInHelper.logDebug(
                      String.format(
                          Locale.getDefault(),
                          "Calling nativeOnResult: handle: %s, status: %d acct: %s",
                          tokenResult.getHandle(),
                          tokenResult.getStatus().getStatusCode(),
                          tokenResult.getAccount()));
                  GoogleSignInHelper.nativeOnResult(
                      tokenResult.getHandle(),
                      tokenResult.getStatus().getStatusCode(),
                      tokenResult.getAccount());
                  clearRequest(false);
                }
              });

      if (startCredentialManagerRequest(silent)) {
        GoogleSignInHelper.logDebug("Started Credential Manager sign-in.");
        return;
      }

      startLegacyRequest(silent);
    } catch (Throwable throwable) {
      GoogleSignInHelper.logError("Exception caught! " + throwable.getMessage());
      finishRequest(CommonStatusCodes.INTERNAL_ERROR, null);
    }

    GoogleSignInHelper.logDebug("Done with processRequest!");
  }

  private boolean startCredentialManagerRequest(final boolean silent) {
    final Activity activity = getActivity();
    if (activity == null
        || request == null
        || !request.canUseCredentialManager()
        || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      return false;
    }

    if (credentialManager == null) {
      credentialManager = CredentialManager.create(activity);
    }

    GetCredentialRequest credentialRequest =
        new GetCredentialRequest.Builder()
            .addCredentialOption(
                silent
                    ? new GetGoogleIdOption.Builder()
                        .setFilterByAuthorizedAccounts(true)
                        .setServerClientId(request.getWebClientId())
                        .setAutoSelectEnabled(true)
                        .build()
                    : new GetSignInWithGoogleOption.Builder(request.getWebClientId()).build())
            .build();

    credentialManager.getCredentialAsync(
        activity,
        credentialRequest,
        null,
        mainExecutor,
        new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
          @Override
          public void onResult(@NonNull GetCredentialResponse result) {
            if (!handleCredentialResult(result)) {
              GoogleSignInHelper.logError("Unexpected Credential Manager response, using legacy.");
              fallbackToLegacy(silent, CommonStatusCodes.ERROR);
            }
          }

          @Override
          public void onError(@NonNull GetCredentialException e) {
            if (e instanceof GetCredentialCancellationException) {
              finishRequest(CommonStatusCodes.CANCELED, null);
              return;
            }

            GoogleSignInHelper.logError(
                "Credential Manager failed, using legacy: " + e.getMessage());
            fallbackToLegacy(silent, mapCredentialException(e));
          }
        });
    return true;
  }

  private boolean handleCredentialResult(GetCredentialResponse result) {
    Credential credential = result.getCredential();
    if (!(credential instanceof CustomCredential)) {
      return false;
    }

    CustomCredential customCredential = (CustomCredential) credential;
    String credentialType = customCredential.getType();
    if (!GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credentialType)
        && !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL.equals(credentialType)) {
      return false;
    }

    try {
      GoogleIdTokenCredential googleCredential =
          GoogleIdTokenCredential.createFrom(customCredential.getData());
      finishRequest(
          CommonStatusCodes.SUCCESS,
          GoogleSignInUserData.fromGoogleIdTokenCredential(googleCredential));
      return true;
    } catch (RuntimeException e) {
      GoogleSignInHelper.logError("Failed to parse Google ID token: " + e.getMessage());
      return false;
    }
  }

  private void fallbackToLegacy(boolean silent, int fallbackStatus) {
    if (request == null) {
      return;
    }
    try {
      startLegacyRequest(silent);
    } catch (Throwable throwable) {
      GoogleSignInHelper.logError("Legacy Google Sign-In fallback failed: " + throwable);
      finishRequest(fallbackStatus, null);
    }
  }

  private int mapCredentialException(GetCredentialException exception) {
    if (exception instanceof GetCredentialCancellationException) {
      return CommonStatusCodes.CANCELED;
    }
    if (exception instanceof NoCredentialException) {
      return CommonStatusCodes.SIGN_IN_REQUIRED;
    }
    return CommonStatusCodes.ERROR;
  }

  private void startLegacyRequest(final boolean silent) {
    buildClient(request);
    if (mGoogleApiClient == null) {
      return;
    }

    GoogleSignInHelper.logDebug(
        " Has connected == " + mGoogleApiClient.hasConnectedApi(Auth.GOOGLE_SIGN_IN_API));
    if (mGoogleApiClient.hasConnectedApi(Auth.GOOGLE_SIGN_IN_API)) {
      legacySilentSignIn();
    } else if (!silent) {
      Intent signInIntent = Auth.GoogleSignInApi.getSignInIntent(mGoogleApiClient);
      startActivityForResult(signInIntent, RC_SIGNIN);
    } else {
      legacySilentSignIn();
    }
  }

  private void legacySilentSignIn() {
    Auth.GoogleSignInApi.silentSignIn(mGoogleApiClient)
        .setResultCallback(
            new ResultCallback<GoogleSignInResult>() {
              @Override
              public void onResult(@NonNull GoogleSignInResult googleSignInResult) {
                if (!googleSignInResult.isSuccess()) {
                  GoogleSignInHelper.logError(
                      "Error with silentSignIn: " + googleSignInResult.getStatus());
                }
                finishLegacyResult(googleSignInResult);
              }
            });
  }

  private void finishLegacyResult(@Nullable GoogleSignInResult result) {
    if (result == null) {
      finishRequest(CommonStatusCodes.ERROR, null);
      return;
    }

    GoogleSignInAccount acct = result.getSignInAccount();
    finishRequest(
        result.getStatus().getStatusCode(),
        GoogleSignInUserData.fromGoogleSignInAccount(acct));
  }

  private void finishRequest(int status, @Nullable GoogleSignInUserData account) {
    TokenRequest currentRequest = request;
    if (currentRequest != null) {
      currentRequest.setResult(status, account);
    } else {
      GoogleSignInHelper.logError("Pending request is null, can't return result!");
    }
  }

  /**
   * Builds the Google API Client based on the configuration in the request.
   *
   * @param request - the request for a token.
   */
  private void buildClient(TokenRequest request) {
    GoogleSignInOptions.Builder builder;

    if (request.getUseGamesConfig()) {
      GoogleSignInHelper.logDebug("Using DEFAULT_GAMES_SIGN_IN");
      builder = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_GAMES_SIGN_IN);
    } else {
      GoogleSignInHelper.logDebug("Using DEFAULT_SIGN_IN");
      builder = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN);
    }

    if (request.getDoAuthCode()) {
      if (!request.getWebClientId().isEmpty()) {
        GoogleSignInHelper.logDebug(
            "Requesting AuthCode force = "
                + request.getForceRefresh()
                + "client: "
                + request.getWebClientId());
        builder.requestServerAuthCode(request.getWebClientId(), request.getForceRefresh());
      } else {
        GoogleSignInHelper.logError("Web client ID is needed for Auth Code");
        finishRequest(CommonStatusCodes.DEVELOPER_ERROR, null);
        throw new IllegalStateException("Web client ID is needed for Auth Code");
      }
    }

    if (request.getDoEmail()) {
      GoogleSignInHelper.logDebug("Requesting email");
      builder.requestEmail();
    }

    if (request.getDoIdToken()) {
      if (!request.getWebClientId().isEmpty()) {
        GoogleSignInHelper.logDebug("Requesting IDToken  client: " + request.getWebClientId());

        builder.requestIdToken(request.getWebClientId());
      } else {
        GoogleSignInHelper.logError("Web client ID is needed for ID Token");
        finishRequest(CommonStatusCodes.DEVELOPER_ERROR, null);
        throw new IllegalStateException("Web client ID is needed for ID Token");
      }
    }
    if (request.getScopes() != null) {
      for (String s : request.getScopes()) {
        GoogleSignInHelper.logDebug("Adding scope: " + s);

        builder.requestScopes(new Scope(s));
      }
    }

    if (request.getHidePopups() && request.getUseGamesConfig()) {
      GoogleSignInHelper.logDebug("hiding popup views for games API");
      builder.addExtension(getGamesExtension());
    }

    if (request.getAccountName() != null) {
      GoogleSignInHelper.logDebug("Setting accountName: " + request.getAccountName());

      builder.setAccountName(request.getAccountName());
    }

    GoogleSignInOptions options = builder.build();

    GoogleApiClient.Builder clientBuilder =
        new GoogleApiClient.Builder(getActivity()).addApi(Auth.GOOGLE_SIGN_IN_API, options);
    if (request.getUseGamesConfig()) {
      GoogleSignInHelper.logDebug("Adding games API");

      try {
        clientBuilder.addApi(getGamesAPI());
      } catch (Exception e) {
        GoogleSignInHelper.logError("Exception getting Games API: " + e.getMessage());
        finishRequest(CommonStatusCodes.DEVELOPER_ERROR, null);
        return;
      }
    }
    if (request.getHidePopups()) {
      View invisible = new View(getActivity());
      invisible.setVisibility(View.INVISIBLE);
      invisible.setClickable(false);
      clientBuilder.setViewForPopups(invisible);
    }
    mGoogleApiClient = clientBuilder.build();
    mGoogleApiClient.connect(GoogleApiClient.SIGN_IN_MODE_OPTIONAL);
  }

  private Api<? extends Api.ApiOptions.NotRequiredOptions> getGamesAPI() {
    try {
      Class<?> gamesClass = Class.forName("com" + ".google.android.gms.games.Games");
      Field apiField = gamesClass.getField("API");
      return (Api<? extends Api.ApiOptions.NotRequiredOptions>) apiField.get(null);
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException("Games API requested, but can't load Games class", e);
    } catch (NoSuchFieldException e) {
      throw new IllegalArgumentException("Games API requested, but can't load Games API field", e);
    } catch (IllegalAccessException e) {
      throw new IllegalArgumentException("Games API requested, but can't load Games API field", e);
    }
  }

  /**
   * Builds the games extension to hide popups using Reflection. This avoids the hard dependency on
   * Games.
   *
   * @return the extension, or throws InvalidArgumentException if games is requested, but not found.
   */
  private GoogleSignInOptionsExtension getGamesExtension() {
    try {
      Class<?> gamesClass = Class.forName("com" + ".google.android.gms.games.Games$GamesOptions");

      Method builderMethod = gamesClass.getMethod("builder");

      Object builder = builderMethod.invoke(null);

      Method setter = builder.getClass().getMethod("setShowConnectingPopup", boolean.class);

      setter.invoke(builder, false);

      Method buildMethod = builder.getClass().getMethod("build");
      return (GoogleSignInOptionsExtension) buildMethod.invoke(builder);

    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException(
          "Games API requested, but can't load Games$GamesOptions class", e);
    } catch (NoSuchMethodException e) {
      throw new IllegalArgumentException(
          "Games API requested, but can't find builder() static method.", e);
    } catch (InvocationTargetException e) {
      throw new IllegalArgumentException(
          "Games API requested, but can't invoke builder() static method.", e);
    } catch (IllegalAccessException e) {
      throw new IllegalArgumentException(
          "Games API requested, but can't invoke builder() static method.", e);
    }
  }

  @Override
  public void onStart() {
    super.onStart();

    if (mGoogleApiClient != null) {
      mGoogleApiClient.connect(GoogleApiClient.SIGN_IN_MODE_OPTIONAL);
    }
  }

  /**
   * Called when the fragment is visible to the user and actively running. This is generally tied to
   * {@link Activity#onResume() Activity.onResume} of the containing Activity's lifecycle.
   */
  @Override
  public void onResume() {
    GoogleSignInHelper.logDebug("onResume called");
    if (theFragment != this) {
      theFragment = this;
    }
    super.onResume();
    if (getState() == State.PENDING) {
      GoogleSignInHelper.logDebug("State is pending, calling processRequest(false)");
      processRequest(false);
    } else if (getState() == State.PENDING_SILENT) {
      GoogleSignInHelper.logDebug("State is pending_silent, calling processRequest(true)");
      processRequest(true);
    } else {
      GoogleSignInHelper.logDebug("State is now ready");
      setState(State.READY);
    }
  }

  /**
   * Receive the result from a previous call to {@link #startActivityForResult(Intent, int)}. This
   * follows the related Activity API as described there in {@link Activity#onActivityResult(int,
   * int, Intent)}.
   *
   * @param requestCode The integer request code originally supplied to startActivityForResult(),
   *     allowing you to identify who this result came from.
   * @param resultCode The integer result code returned by the child activity through its
   *     setResult().
   * @param data An Intent, which can return result data to the caller.
   */
  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    GoogleSignInHelper.logDebug("onActivityResult: " + requestCode + " " + resultCode);
    if (requestCode == RC_SIGNIN) {
      GoogleSignInResult result = Auth.GoogleSignInApi.getSignInResultFromIntent(data);
      if (request != null) {
        if (result == null) {
          int returnCode = resultCode >= 0 ? CommonStatusCodes.ERROR : resultCode;
          finishRequest(returnCode, null);
          GoogleSignInHelper.logError("GoogleSignIn result is null, returning error.");
        } else {
          finishLegacyResult(result);
        }
      } else {
        GoogleSignInHelper.logError("Pending request is null, can't return result!");
      }
      return;
    }
    super.onActivityResult(requestCode, resultCode, data);
  }

  private void clearCredentialManagerState() {
    Activity activity = getActivity();
    if (activity == null) {
      return;
    }

    if (credentialManager == null) {
      credentialManager = CredentialManager.create(activity);
    }

    credentialManager.clearCredentialStateAsync(
        new ClearCredentialStateRequest(),
        null,
        mainExecutor,
        new CredentialManagerCallback<Void, ClearCredentialException>() {
          @Override
          public void onResult(@Nullable Void result) {
            GoogleSignInHelper.logDebug("Credential Manager state cleared.");
          }

          @Override
          public void onError(@NonNull ClearCredentialException e) {
            GoogleSignInHelper.logError(
                "Failed to clear Credential Manager state: " + e.getMessage());
          }
        });
  }

  private synchronized void clearRequest(boolean cancel) {
    if (cancel && request != null) {
      request.cancel();
    }
    request = null;
    setState(getActivity() != null ? State.READY : State.NEW);
  }
}
