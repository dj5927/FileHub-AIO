FileHub AIO PC 1.3.144 - OAuth selective-consent TEST ONLY

Exit FileHub AIO and safely disconnect drives. Copy the installed/extracted v1.3.144 folder to a separate test folder. Replace only FileHubAIO.exe in that copied folder with the executable from this artifact. Keep the original tools/ directory. Start the test copy. Reauthorize Google Drive. The test requests drive and userinfo.email and verifies the granted Drive scope before connecting. The Google Cloud data access configuration and the actual OAuth request must match. Do not submit a mismatched-scope review video or distribute this test as the production release. No production OTA change.

Local Windows regression suites passed: google-direct-api-policy-test, google-login-ui-policy-test, google-publisher-oauth-policy-test, google-cold-start-policy-test. Live Google consent UI remains unverified. The binary is reconstructed from the locally tested executable delta and SHA-256 checked.
