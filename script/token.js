#! node
import * as app from 'firebase-admin/app';
import * as auth from 'firebase-admin/auth';
import fetch from 'node-fetch';

async function main() {
    app.initializeApp({
        credential: app.applicationDefault(),
        databaseURL: 'https://firescript-577a2.firebaseio.com'
    });

    const customToken = await auth.getAuth().createCustomToken('github-community-extensions');
    // console.log('custom token:', customToken);

    const apiKey = process.env['FIREBASE_WEB_API_KEY'];

    const res = await fetch("https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=" + apiKey, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({
            'token': customToken,
            'returnSecureToken': true
        })
    });

    const json = await res.json();
    // console.log('id token:', json);
    return json.idToken;
}

main().then(console.log);