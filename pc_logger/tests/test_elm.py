import pytest

from pc_logger import elm
from pc_logger.pids import ALL_PIDS


def get_pid(code: str):
    return next(p for p in ALL_PIDS if p.pid == code)


def test_extract_data_bytes_clean_response():
    # "41 0C 1A F8" with spaces already stripped by the transport layer's ATS0
    data = elm._extract_data_bytes("410C1AF8", "41", "0C")
    assert data == [0x1A, 0xF8]


def test_extract_data_bytes_with_leftover_spaces():
    data = elm._extract_data_bytes("41 0C 1A F8", "41", "0C")
    assert data == [0x1A, 0xF8]


def test_extract_data_bytes_strips_searching_notice_and_keeps_data():
    # Slow-init protocols (ISO 9141-2 / KWP) print SEARCHING... and can still
    # return real data afterward; that shouldn't be treated as NO DATA.
    data = elm._extract_data_bytes("SEARCHING...410C1AF8", "41", "0C")
    assert data == [0x1A, 0xF8]


@pytest.mark.parametrize("marker_text", ["NO DATA", "STOPPED", "UNABLE TO CONNECT", "BUS INIT", "CAN ERROR", "?"])
def test_extract_data_bytes_raises_no_data_on_known_markers(marker_text):
    with pytest.raises(elm.NoDataError):
        elm._extract_data_bytes(marker_text, "41", "0C")


def test_extract_data_bytes_raises_adapter_error_when_prefix_missing():
    with pytest.raises(elm.AdapterError):
        elm._extract_data_bytes("SOMETHING UNRELATED FF FF", "41", "0C")


class _FakeTransport:
    """Stands in for SerialTransport so query_pid can be tested without hardware."""

    def __init__(self, response: str, latency_ms: int = 42):
        self.response = response
        self.latency_ms = latency_ms
        self.sent: list[str] = []

    def send_command(self, command, timeout_s=None):
        self.sent.append(command)
        return self.response, self.latency_ms


def test_query_pid_happy_path():
    transport = _FakeTransport("410C1AF8")
    value, latency = elm.query_pid(transport, get_pid("0C"))
    assert value == (26 * 256 + 248) / 4
    assert latency == 42
    assert transport.sent == ["010C"]


def test_query_pid_raises_on_short_response():
    transport = _FakeTransport("410C1A")  # only 1 data byte, RPM needs 2
    with pytest.raises(elm.AdapterError):
        elm.query_pid(transport, get_pid("0C"))


def test_read_dtcs_strips_mode_echo_and_decodes():
    # 43 (mode echo) 01 71 (P0171) 00 00 (filler, no more codes)
    transport = _FakeTransport("4301710000")
    codes = elm.read_dtcs(transport, "03")
    assert codes == ["P0171"]


def test_read_dtcs_returns_empty_on_no_data():
    transport = _FakeTransport("NO DATA")
    assert elm.read_dtcs(transport, "03") == []


def test_read_vin_happy_path():
    vin_ascii = "1YVHP80C785M12345"
    hex_payload = "".join(f"{ord(c):02X}" for c in vin_ascii)
    transport = _FakeTransport("490201" + hex_payload)
    assert elm.read_vin(transport) == vin_ascii
