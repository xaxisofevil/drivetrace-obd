import pytest

from pc_logger.probe import READ_ONLY_SERVICES, Probe, classify


def test_classify_silent():
    assert classify("NO DATA", "3E00")["status"] == "silent"
    assert classify("", "3E00")["status"] == "silent"


def test_classify_positive_with_header():
    r = classify("728 06 62 2A 05 20 21 22", "222A05")
    assert r["status"] == "positive"


def test_classify_negative_names_the_nrc():
    r = classify("728 03 7F 22 31", "222A05")
    assert r == {"status": "negative", "nrc": "31", "detail": "requestOutOfRange"}


def test_classify_adapter_error():
    assert classify("CAN ERROR", "3E00")["status"] == "adapter_error"


class _Fake:
    def __init__(self):
        self.sent = []

    def send_command(self, cmd, timeout_s=None):
        self.sent.append(cmd)
        return "NO DATA", 1


@pytest.mark.parametrize("request_hex", ["2E1234AA", "2F0100", "3101FF00", "1003", "2701", "1101", "14FFFFFF"])
def test_write_services_never_leave_the_probe(tmp_path, request_hex):
    fake = _Fake()
    probe = Probe(fake, tmp_path / "out.jsonl")
    with pytest.raises(ValueError):
        probe._send_uds("7E0", request_hex)
    assert fake.sent == []
    probe.close()


def test_read_services_are_sent(tmp_path):
    fake = _Fake()
    probe = Probe(fake, tmp_path / "out.jsonl")
    probe._send_uds("720", "222A05")
    assert fake.sent == ["ATSH720", "222A05"]
    assert 0x22 in READ_ONLY_SERVICES
    probe.close()
