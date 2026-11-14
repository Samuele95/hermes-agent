"""A provider whose only pool row is a secret-free ``env:VAR`` reference is not authenticated in the picker once VAR is gone.

An agent build (``resolve_runtime_provider`` -> ``load_pool``) persists the row while VAR is set, and the row is kept
when VAR goes (#9331); a host that revokes VAR (a secret source, the user) must see the picker follow."""


def test_a_reference_row_whose_variable_is_gone_does_not_authenticate_the_picker(monkeypatch):
    from agent.credential_pool import load_pool
    from hermes_cli.model_switch_providers import _raw_pool_usable

    monkeypatch.setenv("DEEPSEEK_API_KEY", "sk-test-not-a-real-key")
    load_pool("deepseek")  # seeds and persists the env: reference row, as an agent build does
    assert _raw_pool_usable("deepseek", for_picker=True)

    monkeypatch.delenv("DEEPSEEK_API_KEY")
    assert load_pool("deepseek").entries(), "the row stays on disk (#9331)"
    assert load_pool("deepseek").has_credentials(), "has_credentials() keeps counting the row"
    assert not _raw_pool_usable("deepseek", for_picker=True)


def test_a_hydrated_pool_in_cooldown_stays_visible_to_the_picker(monkeypatch):
    from agent.credential_pool import CredentialPool, load_pool
    from hermes_cli.model_switch_providers import _raw_pool_usable

    monkeypatch.setenv("DEEPSEEK_API_KEY", "sk-test-not-a-real-key")
    load_pool("deepseek")
    monkeypatch.setattr(CredentialPool, "has_available", lambda self, **_: False)
    assert _raw_pool_usable("deepseek", for_picker=True)
    assert not _raw_pool_usable("deepseek", for_picker=False)
