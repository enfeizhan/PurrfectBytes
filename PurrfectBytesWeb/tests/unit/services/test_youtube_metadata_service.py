"""Tests for YouTube metadata credit injection and item extraction."""

import pytest

from src.services.youtube_metadata_service import YouTubeMetadataService

SAMPLE_DESCRIPTION = """📚 Study Journal Entry #42

Some intro text.

💡 Study Tip:

Read it aloud.

📌 Credit:

This sentence is sourced from another creator's content. All credit goes to the original author.

👍 Enjoyed this study session? Please give it a thumbs up!

🔔 Subscribe to follow my language learning journey and practice together!

#LanguageLearning"""


class TestApplyCredit:
    def test_replaces_generic_credit_paragraph(self):
        credit = "This sentence comes from the textbook 新完全マスター N1 by 3A Corporation."
        result = YouTubeMetadataService._apply_credit(SAMPLE_DESCRIPTION, credit)

        assert credit in result
        assert "another creator's content" not in result
        # Everything around the credit section is preserved
        assert result.startswith("📚 Study Journal Entry #42")
        assert "💡 Study Tip:" in result
        assert "👍 Enjoyed this study session?" in result
        assert "🔔 Subscribe" in result
        assert result.endswith("#LanguageLearning")
        # Section structure intact: header, blank line, credit, blank line, thumbs-up
        assert f"📌 Credit:\n\n{credit}\n\n👍" in result

    def test_appends_section_when_markers_missing(self):
        description = "Just some text without the expected sections."
        credit = "From my favorite podcast."
        result = YouTubeMetadataService._apply_credit(description, credit)

        assert result.startswith(description)
        assert result.endswith(f"📌 Credit:\n\n{credit}")

    def test_credit_with_special_characters_is_verbatim(self):
        credit = 'Source: "Nihongo (日本語) \\ podcast" — episode #5 ($ & *)'
        result = YouTubeMetadataService._apply_credit(SAMPLE_DESCRIPTION, credit)
        assert credit in result


ITEMS_JSON = (
    '[{"kind": "vocabulary", "term": "元気", "phonetics": "げんき", "meaning": "healthy; well"},'
    ' {"kind": "grammar", "term": "ですか", "phonetics": "ですか", "meaning": "polite question ending"}]'
)


class TestParseItemsResponse:
    def test_clean_json_array(self):
        items = YouTubeMetadataService._parse_items_response(ITEMS_JSON)
        assert [i["kind"] for i in items] == ["vocabulary", "grammar"]
        assert items[0]["term"] == "元気"
        assert items[1]["meaning"] == "polite question ending"

    def test_fenced_json_with_prose(self):
        raw = f"Here are the items:\n```json\n{ITEMS_JSON}\n```\nHope this helps!"
        items = YouTubeMetadataService._parse_items_response(raw)
        assert len(items) == 2

    def test_malformed_entries_skipped_and_kind_normalized(self):
        raw = (
            '[{"kind": "word", "term": "a", "meaning": "b"},'
            ' {"term": "no meaning here"},'
            ' "not a dict",'
            ' {"kind": "GRAMMAR", "term": "c", "meaning": "d"}]'
        )
        items = YouTubeMetadataService._parse_items_response(raw)
        assert len(items) == 2
        assert items[0]["kind"] == "vocabulary"  # unknown kind defaults
        assert items[0]["phonetics"] == ""       # missing phonetics tolerated
        assert items[1]["kind"] == "grammar"     # case-insensitive

    def test_no_array_raises(self):
        with pytest.raises(ValueError, match="no item list"):
            YouTubeMetadataService._parse_items_response("Sorry, I can't do that.")

    def test_invalid_json_raises(self):
        with pytest.raises(ValueError, match="not valid JSON"):
            YouTubeMetadataService._parse_items_response("[{'single': 'quotes'}]")

    def test_all_entries_unusable_raises(self):
        with pytest.raises(ValueError, match="try again"):
            YouTubeMetadataService._parse_items_response('[{"term": ""}, 42]')


class TestExtractItems:
    def test_extract_items_parses_provider_response(self, mocker):
        service = YouTubeMetadataService()
        generate = mocker.patch.object(
            service, "_generate_gemini", return_value=f"```json\n{ITEMS_JSON}\n```"
        )
        items = service.extract_items("元気ですか", provider="gemini")
        assert len(items) == 2
        prompt = generate.call_args.args[0]
        assert "元気ですか" in prompt
        assert "strict JSON array" in prompt

    def test_extract_items_rejects_bad_input(self):
        service = YouTubeMetadataService()
        with pytest.raises(ValueError, match="No sentence provided"):
            service.extract_items("   ")
        with pytest.raises(ValueError, match="Unknown provider"):
            service.extract_items("Hello world", provider="bard")


class TestGenerateWithApprovedItems:
    CANNED_RESPONSE = 'My Study Journal: Japanese Sentence - "元気ですか" | Reading & Pronunciation\n\nBody.'

    def _generate(self, mocker, **kwargs):
        service = YouTubeMetadataService()
        mock = mocker.patch.object(
            service, "_generate_gemini", return_value=self.CANNED_RESPONSE
        )
        service.generate("元気ですか", provider="gemini", **kwargs)
        return mock.call_args.args[0]

    def test_items_constrain_the_prompt(self, mocker):
        items = [
            {"kind": "vocabulary", "term": "元気", "phonetics": "げんき", "meaning": "healthy"},
            {"kind": "grammar", "term": "ですか", "phonetics": "", "meaning": "question ending"},
        ]
        prompt = self._generate(mocker, items=items)
        assert "APPROVED ITEMS OVERRIDE" in prompt
        assert "- 元気 (げんき): healthy" in prompt
        assert "- ですか: question ending" in prompt
        # Override lands before the target sentence line
        assert prompt.index("APPROVED ITEMS OVERRIDE") < prompt.rindex("TARGET SENTENCE:")

    def test_empty_kind_marked_as_none(self, mocker):
        items = [{"kind": "vocabulary", "term": "元気", "phonetics": "", "meaning": "healthy"}]
        prompt = self._generate(mocker, items=items)
        assert "Approved grammar items:\n(none" in prompt

    def test_without_items_prompt_has_no_override(self, mocker):
        prompt = self._generate(mocker)
        assert "APPROVED ITEMS OVERRIDE" not in prompt
