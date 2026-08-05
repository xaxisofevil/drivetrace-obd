from pc_logger.pids import ALL_PIDS, decode_dtc_pair, decode_dtcs, decode_vin


def get_pid(code: str):
    return next(p for p in ALL_PIDS if p.pid == code)


def test_rpm_formula():
    # 41 0C 1A F8 -> ((0x1A*256)+0xF8)/4
    assert get_pid("0C").parse([0x1A, 0xF8]) == (26 * 256 + 248) / 4


def test_speed_is_raw_byte():
    assert get_pid("0D").parse([0x64]) == 100  # 100 km/h


def test_coolant_temp_offset():
    # 0x7B (123) -> 123-40 = 83C
    assert get_pid("05").parse([0x7B]) == 83


def test_fuel_trim_formula_centered_at_128():
    stft = get_pid("06")
    assert stft.parse([128]) == 0  # neutral trim
    assert round(stft.parse([0]), 2) == -100.0
    assert round(stft.parse([255]), 2) == round((255 - 128) * 100 / 128, 2)


def test_maf_formula():
    maf = get_pid("10")
    assert maf.parse([0x01, 0x2C]) == (1 * 256 + 44) / 100  # 3.00 g/s


def test_commanded_equivalence_ratio_at_stoich():
    ce = get_pid("44")
    # lambda=1 => raw value ~32768 => bytes 0x80 0x00
    assert round(ce.parse([0x80, 0x00]), 3) == 1.0


def test_control_module_voltage():
    voltage = get_pid("42")
    # 14.20V => raw 14200 => 0x3778
    assert round(voltage.parse([0x37, 0x78]), 2) == 14.2


def test_decode_dtc_pair_known_code():
    # P0171 = 0000 0001 0111 0001 -> byte1=0x01, byte2=0x71
    assert decode_dtc_pair(0x01, 0x71) == "P0171"


def test_decode_dtc_pair_chassis_code():
    # C: top bits 01. byte1 = 0b01_00_0001 = 0x41, byte2=0x23 -> C0123
    assert decode_dtc_pair(0x41, 0x23) == "C0123"


def test_decode_dtc_pair_filler_is_none():
    assert decode_dtc_pair(0, 0) is None


def test_decode_dtcs_multiple_with_filler():
    # P0171 then filler then C0123
    data = [0x01, 0x71, 0x00, 0x00, 0x41, 0x23]
    assert decode_dtcs(data) == ["P0171", "C0123"]


def test_decode_vin_strips_non_printable_bytes():
    vin_ascii = "1YVHP80C785M12345"
    assert len(vin_ascii) == 17
    data = [0x00] + [ord(c) for c in vin_ascii]  # leading non-printable count byte
    assert decode_vin(data) == vin_ascii


def test_decode_vin_truncates_to_last_17_when_extra_printable_prefix():
    vin_ascii = "1YVHP80C785M12345"
    data = [ord("0")] + [ord(c) for c in vin_ascii]  # printable leading index char
    assert decode_vin(data) == vin_ascii
