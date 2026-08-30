"""Unit tests for video generation helpers."""

from PIL import Image, ImageDraw, ImageFont

from src.services.video_generation import _compute_char_layout, _map_text_positions


class TestCharLayoutAlignment:
    def _layout(self, align):
        img = Image.new('RGB', (1280, 720))
        draw = ImageDraw.Draw(img)
        font = ImageFont.load_default()
        return _compute_char_layout(["Hi", "Bye"], font, draw, 1280, 720, align=align)

    def test_left_alignment_starts_every_line_at_padding(self):
        layout = self._layout("left")
        assert layout[0]['x'] == 50   # first char of "Hi"
        assert layout[2]['x'] == 50   # first char of "Bye"

    def test_center_alignment_centers_lines(self):
        layout = self._layout("center")
        assert layout[0]['x'] > 50
        # Lines of different widths start at different x when centered
        assert layout[0]['x'] != layout[2]['x']


class TestCharLayoutLineHeight:
    def _line_spacing(self, size):
        img = Image.new('RGB', (1280, 720))
        draw = ImageDraw.Draw(img)
        font = ImageFont.load_default(size=size)
        layout = _compute_char_layout(["Hi", "Bye"], font, draw, 1280, 720)
        return layout[2]['y'] - layout[0]['y']

    def test_line_height_scales_with_font_size(self):
        """Big fonts get proportionally more line spacing, so lines never overlap."""
        assert self._line_spacing(48) == 70  # historical default preserved
        assert self._line_spacing(96) == 140

    def test_many_lines_never_start_above_frame(self):
        img = Image.new('RGB', (1280, 720))
        draw = ImageDraw.Draw(img)
        font = ImageFont.load_default(size=96)
        layout = _compute_char_layout(["Hi"] * 12, font, draw, 1280, 720)
        assert layout[0]['y'] >= 0


def _layout_for(displayed: str):
    return [{'char': char} for char in displayed]


class TestMapTextPositions:
    """Text position → layout index mapping used for highlighting."""

    def test_identity_when_nothing_dropped(self):
        assert _map_text_positions("abc", _layout_for("abc")) == [0, 1, 2]

    def test_newlines_map_to_none(self):
        # Wrapping drops the newline, so layout holds "abcd"
        mapping = _map_text_positions("ab\ncd", _layout_for("abcd"))
        assert mapping == [0, 1, None, 2, 3]

    def test_space_dropped_at_wrap_boundary(self):
        # "hello world" wrapped into two lines drops the separating space
        mapping = _map_text_positions("hello world", _layout_for("helloworld"))
        assert mapping[:5] == [0, 1, 2, 3, 4]
        assert mapping[5] is None
        assert mapping[6:] == [5, 6, 7, 8, 9]

    def test_dialogue_slice_offsets_stay_aligned(self):
        # Joined dialogue "Hi\nBye" displayed as "HiBye": positions of the
        # second line (offset 3) land on layout entries 2..4
        mapping = _map_text_positions("Hi\nBye", _layout_for("HiBye"))
        assert [mapping[3 + k] for k in range(3)] == [2, 3, 4]
