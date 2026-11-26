# Android multilingual input compatibility

Direct 0.1.156 / Play 1.0.39

Observed: Samsung keyboard works, some other keyboards cannot switch English/Korean in rename and server name fields; native search works.

The Flutter rename prompt and remote editor had enableSuggestions:false for all text. On Android, this can disable language layouts that depend on candidate/composing support. See Flutter issues #139143 and #156801. Android InputType guidance recommends disabling autocorrection rather than disabling suggestions when only automatic spelling correction is unwanted.

Ordinary text inputs now request TextInputType.text, enableSuggestions:true, autocorrect:false, no personalized IME learning, and disabled smart quote/dash transformations. Password/PAT/session input stays masked with suggestions disabled; port remains numeric and digits-only. Native text editor no longer requests TYPE_TEXT_FLAG_NO_SUGGESTIONS for multilingual content, and keeps auto-correction off. Native search is unchanged.

Verification: Flutter input configuration and mixed Korean/English composition tests cover the rename and server form. Android IME flags and actual third-party keyboard behavior must be distinguished; no claim of testing every OEM or external keyboard. Existing authentication, storage, and transfer behavior is unchanged.
