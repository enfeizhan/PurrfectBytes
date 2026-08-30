"""YouTube metadata generation service with multi-LLM provider support."""

import json

from typing import List, Optional

from src.config.settings import GEMINI_API_KEY, OPENAI_API_KEY, ANTHROPIC_API_KEY
from src.utils.logger import get_logger

logger = get_logger(__name__)

YOUTUBE_PROMPT_TEMPLATE = """STRICT OPERATING MODE: Generate the requested content and immediately stop. Do not include any conversational filler, follow-up suggestions, or questions. Any text following the hashtags is a violation of this instruction.

You are a YouTube content creator helping generate titles and descriptions for language learning videos. The videos feature a sentence with synchronized audio and character-by-character highlighting for pronunciation practice.

IMPORTANT RULES:

ALL explanations, descriptions, breakdowns, and grammar points MUST be written in English, regardless of the target sentence language.

NEVER ask follow-up questions - generate the complete output immediately based on the given sentence.

BOLD FORMATTING RULE: Use SINGLE asterisks (*text*) for bold, never double asterisks. YouTube only renders bold when both asterisks are surrounded by whitespace or a line boundary — an asterisk touching any punctuation (e.g. "(*word*)") renders as a literal asterisk. Therefore NEVER place asterisks adjacent to parentheses, quotes, commas, or other punctuation. In breakdowns and grammar points, bold ONLY the original-script headword; leave the phonetics inside parentheses unformatted. Correct: *メッセージ* (めっせーじ) = Message. Wrong: メッセージ (*めっせーじ*) = Message. Always use ASCII parentheses ( ) with a space before the opening parenthesis — NEVER full-width parentheses （）, which would touch the asterisk and break the bold. In prose sections (intro, Study Tip, translation), NEVER bold a term that sits inside parentheses or quotes — write it there unformatted. Wrong: a simple connective (*word*). Correct: a simple connective (word), or restructure so the bolded term stands free with spaces on both sides.

Identify the language automatically.

CRITICAL: Keep the title under 100 characters (strict limit).

Provide accurate phonetics (if applicable: Japanese→Hiragana, Korean→Romanization, Chinese→Pinyin, etc.). For Japanese, ALWAYS use Hiragana for pronunciations instead of Romaji.

TRANSLATION RULE: If the target sentence is NOT English, you MUST include the "English Translation" section. If the target sentence IS English, you MUST DELETE the "English Translation" section entirely.

MANDATORY FORMATTING for Breakdowns/Grammar: You must start with the [Original Script], followed by the [Phonetics/Hiragana/Romanization/IPA] in parentheses, then the English meaning.

Example for English Breakdown: *Word* (IPA Phonetics) = English Meaning.

Break down the sentence (explanations in English). Be SELECTIVE, not exhaustive — this is not a word-by-word gloss. Include vocabulary, collocations, set phrases, and idioms that an intermediate learner of the target language would plausibly not know. Set the bar by frequency: exclude a candidate only when it ranks among roughly the 500 most frequent words of the target language (numbers, pronouns, greetings, everyday nouns, basic function words, the most elementary verbs). Everything less frequent than that belongs in the breakdown — including mid-frequency verbs, adverbs, and set adverbial phrases that an intermediate learner recognizes but could not confidently produce. Do not skip such an item just because its meaning looks plain once translated. Note that a multi-word phrase is judged as a unit: it can deserve an entry even when each word in it is common.

Give verbs and adjectives in their dictionary/base form, not the inflected form used in the sentence — the inflection itself belongs under Grammar Points.

COVER ALL WORD TYPES, not just the visually complex ones: verbs, adjectives, adverbs, and adverbial phrases deserve entries just as much as compound nouns and technical terms. A sentence whose breakdown contains only nouns has almost certainly missed something.

When a content word is fused with a grammar pattern, list BOTH: the content word in its dictionary form under Breakdown, and the pattern under Grammar Points. Explaining the pattern NEVER excuses omitting the word it attaches to. This applies only to words carrying independent lexical meaning — if a word's whole role in the sentence is to build a pattern already covered under Grammar Points (auxiliaries, light or helper verbs, helper adjectives, copulas), leave it out of Breakdown entirely.

Prefer meaningful multi-word chunks over splitting them into their parts: when a phrase's meaning or usage is not obvious from its individual words, list the whole phrase as one item rather than each word separately. List items in the order they appear in the sentence. Where useful, append a short nuance note after the meaning (register, formality, common pairings).

Highlight 2-4 key grammar points (explanations in English). Sort by type: purely grammatical machinery (particles, conjugations and verb forms, tense/aspect/mood markers, conditionals, sentence endings, connectors, agreement patterns — whatever the target language uses) goes under Grammar Points and NEVER under Breakdown; content vocabulary and lexical chunks go under Breakdown. Never print the same item in both sections — but a content word and a pattern attached to it are two different items, so covering the pattern does not remove the word from Breakdown. Do not drop an item just because it could fit either section. Watch especially for patterns in which an ordinary noun, verb, or preposition carries an abstract structural meaning (equivalents of "in the course of", "as far as", "on the grounds that"): they look like plain vocabulary and are the easiest points to overlook.

COVERAGE CHECK before you output: re-read the sentence from beginning to end and confirm that every word or phrase above beginner level appears in one of the two sections. Add anything you skipped.

Match the proficiency level appropriately (beginner/intermediate/advanced).

Use natural, encouraging tone.

Include relevant hashtags for the specific language.

Terminate the response immediately after the final hashtag. Do not include any text, sign-offs, or questions after the hashtags.

Given a target sentence, generate:

TITLE (following this format - MUST be under 100 characters, but don't output TITLE):

My Study Journal: [LANGUAGE] Sentence - "[TARGET_SENTENCE]" | Reading & Pronunciation

DESCRIPTION with these sections (don't output DESCRIPTION):

📚 Study Journal Entry

[Brief intro about learning this sentence today - MUST be in English]

📝 Today's Sentence:

[TARGET_SENTENCE in original language]

([Phonetics/Hiragana/Romanization/IPA if applicable])

📖 English Translation:[ONLY include this section if the target language is NOT English. If English, remove this entire section]

"[Translation in English]"

🔤 Breakdown:

• *[Original Script]* ([Phonetics/Hiragana/Romanization/IPA]) = [Meaning in English]

• *[Original Script]* ([Phonetics/Hiragana/Romanization/IPA]) = [Meaning in English]

📚 Grammar Points:

• *[Original Script]* ([Phonetics/Hiragana/Romanization/IPA]) - [Explanation in English]

• *[Original Script]* ([Phonetics/Hiragana/Romanization/IPA]) - [Explanation in English]

🎯 Perfect for:

• [Proficiency level] learners

• [Learning goal 1]

• [Learning goal 2]

💡 Study Tip:

[Helpful context or usage note about this sentence - in English]

📌 Credit:

This sentence is sourced from another creator's content. All credit goes to the original author.

👍 Enjoyed this study session? Please give it a thumbs up!

🔔 Subscribe to follow my language learning journey and practice together!

☕ Want to support more learning content? Scan the QR code (bottom-left corner)—my cat thanks you! 😺

#[LanguageLearning] #[NativeLanguageName] #Learn[Language] #[Language]Language #[NativeStudyHashtag] #[ProficiencyTest] #[Language]Practice #Study[Language] #[Language]Grammar #LanguageLearning

Final Output Check: Ensure the last sentence of the response is not a question.

TARGET SENTENCE: {sentence}"""


EXTRACTION_PROMPT_TEMPLATE = """You are a language-learning content assistant. Analyze the target sentence and extract every vocabulary item and grammar point a learner of that language might want explained. The user will review your list and untick unwanted entries, so LEAN INCLUSIVE: a missed item cannot be added back, but an extra item costs nothing. When unsure whether something is worth explaining, include it.

RULES:

Identify the language automatically. ALL meanings and explanations MUST be written in English.

Vocabulary items ("kind": "vocabulary"): content words, collocations, set phrases, and idioms. Give verbs and adjectives in their dictionary/base form, not the inflected form used in the sentence. Cover ALL word types - verbs, adjectives, adverbs, and adverbial phrases deserve entries just as much as nouns. Prefer meaningful multi-word chunks over splitting them into their parts: when a phrase's meaning or usage is not obvious from its individual words, list the whole phrase as one item.

Grammar points ("kind": "grammar"): purely grammatical machinery - particles, conjugations and verb forms, tense/aspect/mood markers, conditionals, sentence endings, connectors, agreement patterns, whatever the target language uses. Watch especially for patterns in which an ordinary noun, verb, or preposition carries an abstract structural meaning (equivalents of "in the course of", "as far as", "on the grounds that"): they look like plain vocabulary and are the easiest points to overlook.

When a content word is fused with a grammar pattern, list BOTH: the content word in its dictionary form as vocabulary, and the pattern as grammar. But if a word's whole role in the sentence is to build a pattern already listed as grammar (auxiliaries, light or helper verbs, helper adjectives, copulas), list only the pattern.

Never list the same item as both kinds.

List items in the order they appear in the sentence.

Provide accurate phonetics per language (Japanese always Hiragana - never Romaji, Korean Romanization, Chinese Pinyin, English IPA, etc.); use an empty string when not applicable.

OUTPUT FORMAT: Respond with a strict JSON array and NOTHING else - no prose, no markdown fences, no trailing commentary:

[{{"kind": "vocabulary", "term": "original script", "phonetics": "phonetics", "meaning": "meaning or explanation in English"}}, {{"kind": "grammar", "term": "original script", "phonetics": "phonetics", "meaning": "explanation in English"}}]

TARGET SENTENCE: {sentence}"""


class YouTubeMetadataService:
    """Service for generating YouTube titles and descriptions using LLMs."""

    AVAILABLE_PROVIDERS = ["gemini", "openai", "anthropic"]

    def generate(self, sentence: str, provider: str = "gemini",
                 credit: Optional[str] = None,
                 items: Optional[List[dict]] = None) -> dict:
        """
        Generate YouTube title and description for a language learning video.

        Args:
            sentence: The target sentence to generate metadata for
            provider: LLM provider to use (gemini, openai, anthropic)
            credit: Exact credit sentence to place in the 📌 Credit section
                    (replaces the generic credit line verbatim)
            items: User-approved vocabulary/grammar items (from extract_items,
                   after review). When given, the Breakdown and Grammar Points
                   sections are constrained to exactly these items.

        Returns:
            dict with 'title' and 'description' keys
        """
        if not sentence or not sentence.strip():
            raise ValueError("No sentence provided")

        provider = provider.lower()
        if provider not in self.AVAILABLE_PROVIDERS:
            raise ValueError(f"Unknown provider: {provider}. Available: {', '.join(self.AVAILABLE_PROVIDERS)}")

        prompt = YOUTUBE_PROMPT_TEMPLATE.format(sentence=sentence.strip())
        if items is not None:
            # Inject the approved-items constraint just before the target
            # sentence so it overrides the selection rules above it.
            marker = "TARGET SENTENCE:"
            marker_pos = prompt.rfind(marker)
            prompt = (
                prompt[:marker_pos]
                + self._items_override_block(items)
                + "\n\n"
                + prompt[marker_pos:]
            )

        logger.info(f"Generating YouTube metadata with {provider} for: {sentence[:50]}...")

        if provider == "gemini":
            raw_text = self._generate_gemini(prompt)
        elif provider == "openai":
            raw_text = self._generate_openai(prompt)
        elif provider == "anthropic":
            raw_text = self._generate_anthropic(prompt)
        else:
            raise ValueError(f"Provider {provider} not implemented")

        result = self._parse_response(raw_text, sentence.strip())
        if credit:
            result["description"] = self._apply_credit(result["description"], credit)
        return result

    def extract_items(self, sentence: str, provider: str = "gemini") -> List[dict]:
        """
        Extract candidate vocabulary/grammar items from a sentence for review.

        Returns a list of {"kind": "vocabulary"|"grammar", "term", "phonetics",
        "meaning"} dicts in sentence order. Deliberately over-inclusive - the
        user unticks unwanted entries before generation.
        """
        if not sentence or not sentence.strip():
            raise ValueError("No sentence provided")

        provider = provider.lower()
        if provider not in self.AVAILABLE_PROVIDERS:
            raise ValueError(f"Unknown provider: {provider}. Available: {', '.join(self.AVAILABLE_PROVIDERS)}")

        prompt = EXTRACTION_PROMPT_TEMPLATE.format(sentence=sentence.strip())

        logger.info(f"Extracting vocabulary/grammar with {provider} for: {sentence[:50]}...")

        if provider == "gemini":
            raw_text = self._generate_gemini(prompt)
        elif provider == "openai":
            raw_text = self._generate_openai(prompt)
        else:
            raw_text = self._generate_anthropic(prompt)

        return self._parse_items_response(raw_text)

    @staticmethod
    def _parse_items_response(raw_text: str) -> List[dict]:
        """
        Parse the extraction call's JSON array, tolerating fences and prose.

        Slicing from the first "[" to the last "]" strips markdown fences and
        any surrounding commentary. Malformed entries are skipped; an unusable
        response raises ValueError with a user-facing message.
        """
        text = (raw_text or "").strip()
        start = text.find("[")
        end = text.rfind("]")
        if start == -1 or end <= start:
            raise ValueError("Could not extract items - the AI response had no item list")

        try:
            data = json.loads(text[start:end + 1])
        except ValueError:
            raise ValueError("Could not extract items - the AI response was not valid JSON")

        items = []
        for entry in data if isinstance(data, list) else []:
            if not isinstance(entry, dict):
                continue
            term = str(entry.get("term", "")).strip()
            meaning = str(entry.get("meaning", "")).strip()
            if not term or not meaning:
                continue
            kind = str(entry.get("kind", "")).strip().lower()
            items.append({
                "kind": kind if kind == "grammar" else "vocabulary",
                "term": term,
                "phonetics": str(entry.get("phonetics", "") or "").strip(),
                "meaning": meaning,
            })

        if not items:
            raise ValueError("Could not extract items - try again or use a different provider")
        return items

    @staticmethod
    def _items_override_block(items: List[dict]) -> str:
        """Prompt block constraining the two sections to user-approved items."""
        def bullets(kind: str) -> str:
            lines = []
            for item in items:
                if item.get("kind") != kind:
                    continue
                phonetics = item.get("phonetics", "")
                phonetics_part = f" ({phonetics})" if phonetics else ""
                lines.append(f"- {item['term']}{phonetics_part}: {item['meaning']}")
            return "\n".join(lines) or "(none - output this section's header with no bullet items)"

        return (
            "APPROVED ITEMS OVERRIDE: The user has hand-picked the items below after "
            "reviewing an extraction pass. The Breakdown section must contain exactly the "
            "approved vocabulary items and the Grammar Points section exactly the approved "
            "grammar items - every listed item, in the given order, with no additions and "
            "no omissions. This overrides the selection rules above (frequency bar, "
            "selectivity, the 2-4 grammar points guideline, and the coverage check). All "
            "formatting rules still apply; you may polish the phonetics and the wording of "
            "meanings and explanations, but never change which items appear.\n\n"
            "Approved vocabulary items:\n" + bullets("vocabulary") + "\n\n"
            "Approved grammar items:\n" + bullets("grammar")
        )

    @staticmethod
    def _apply_credit(description: str, credit: str) -> str:
        """
        Replace the generic 📌 Credit paragraph with the given sentence, verbatim.

        Uses plain string slicing between the "📌 Credit:" header and the "👍"
        line so user-written credit text needs no escaping. If either marker is
        missing from the LLM output, the credit is appended as its own section.
        """
        credit = credit.strip()
        header = "📌 Credit:"
        header_pos = description.find(header)
        if header_pos != -1:
            tail_pos = description.find("👍", header_pos)
            if tail_pos != -1:
                return (
                    description[:header_pos + len(header)]
                    + f"\n\n{credit}\n\n"
                    + description[tail_pos:]
                )

        logger.warning("Credit section markers not found; appending credit section")
        return f"{description.rstrip()}\n\n{header}\n\n{credit}"

    def get_available_providers(self) -> list[dict]:
        """Return list of available providers and their status."""
        return [
            {
                "id": "gemini",
                "name": "Google Gemini",
                "available": bool(GEMINI_API_KEY),
                "model": "gemini-3.5-flash",
            },
            {
                "id": "openai",
                "name": "OpenAI",
                "available": bool(OPENAI_API_KEY),
                "model": "gpt-5.4-mini",
            },
            {
                "id": "anthropic",
                "name": "Anthropic Claude",
                "available": bool(ANTHROPIC_API_KEY),
                "model": "claude-sonnet-4-5-20250929",
            },
        ]

    def _generate_gemini(self, prompt: str) -> str:
        """Generate using Google Gemini."""
        if not GEMINI_API_KEY:
            raise ValueError("GEMINI_API_KEY environment variable is not set")

        from google import genai

        client = genai.Client(api_key=GEMINI_API_KEY)
        response = client.models.generate_content(
            model="gemini-3.5-flash",
            contents=prompt,
        )
        return response.text

    def _generate_openai(self, prompt: str) -> str:
        """Generate using OpenAI."""
        if not OPENAI_API_KEY:
            raise ValueError("OPENAI_API_KEY environment variable is not set")

        import openai

        client = openai.OpenAI(api_key=OPENAI_API_KEY)
        response = client.chat.completions.create(
            model="gpt-5.4-mini",
            messages=[{"role": "user", "content": prompt}],
            temperature=0.7,
        )
        return response.choices[0].message.content

    def _generate_anthropic(self, prompt: str) -> str:
        """Generate using Anthropic Claude."""
        if not ANTHROPIC_API_KEY:
            raise ValueError("ANTHROPIC_API_KEY environment variable is not set")

        import anthropic

        client = anthropic.Anthropic(api_key=ANTHROPIC_API_KEY)
        response = client.messages.create(
            model="claude-sonnet-4-5-20250929",
            max_tokens=2000,
            messages=[{"role": "user", "content": prompt}],
        )
        return response.content[0].text

    def _parse_response(self, raw_text: str, original_sentence: str) -> dict:
        """
        Parse LLM response into title and description.
        """
        import re

        lines = raw_text.strip().split("\n")

        # Find first non-empty line as potential title
        raw_title = ""
        description_start = 0
        for i, line in enumerate(lines):
            stripped = line.strip()
            if stripped:
                raw_title = stripped
                description_start = i + 1
                break

        # Rest is description - also strip any leading "DESCRIPTION:" label
        description = "\n".join(lines[description_start:]).strip()
        # Remove leading "DESCRIPTION:" label if the LLM included it
        description = re.sub(r'^DESCRIPTION:\s*\n?', '', description, flags=re.IGNORECASE).strip()

        # Clean up title (remove any "TITLE:" prefix, markdown, etc.)
        title = raw_title
        for prefix in ["TITLE:", "Title:", "title:"]:
            if title.lower().startswith(prefix.lower()):
                title = title[len(prefix):].strip()
        
        # Strip markdown bold markers if present
        title = title.strip().replace("**", "").replace("__", "").strip()

        # Try to extract language and sentence from the title line
        # Use a greedy regex for the quoted sentence to handle apostrophes like "I'll"
        found_language = None
        found_sentence = None

        # Try full pattern match first: My Study Journal: <Language> Sentence - "<sentence>" | ...
        full_match = re.search(
            r'My Study Journal:\s*([a-zA-Z]+)\s+Sentence\s*-\s*["\u201c](.*?)["\u201d]\s*\|',
            title, re.IGNORECASE
        )
        if full_match:
            found_language = full_match.group(1).strip()
            found_sentence = full_match.group(2).strip()
        else:
            # Try language extraction alone
            lang_match = re.search(r'My Study Journal:\s*([a-zA-Z]+)\s+Sentence\s*-', title, re.IGNORECASE)
            if lang_match:
                found_language = lang_match.group(1).strip()

            # Try sentence extraction: get content between the LAST pair of quotes
            # This handles apostrophes like "I'll tee up..."
            quote_matches = re.findall(r'["\u201c](.*?)["\u201d]', title)
            if quote_matches:
                found_sentence = quote_matches[-1].strip() if quote_matches[-1].strip() else None
            
            if not found_sentence:
                # Fallback: content between - and |
                fallback_match = re.search(r'-\s*(.*?)\s*\|', title)
                if fallback_match:
                    found_sentence = fallback_match.group(1).strip().strip('"').strip('\u201c').strip('\u201d').strip()

        # Reconstruct to guarantee format and 100-char limit
        if found_language or found_sentence:
            language_to_use = found_language or "Language"
            sentence_to_use = found_sentence or original_sentence[:50]
            
            prefix = f'My Study Journal: {language_to_use} Sentence - "'
            suffix = '" | Reading & Pronunciation'

            if len(prefix) + len(sentence_to_use) + len(suffix) > 100:
                allowed_len = 100 - len(prefix) - len(suffix) - 3
                if allowed_len > 0:
                    sentence_to_use = sentence_to_use[:allowed_len].strip() + "..."
                    final_title = f'{prefix}{sentence_to_use}{suffix}'
                else:
                    final_title = (prefix + sentence_to_use + suffix)[:97] + "..."
            else:
                final_title = f'{prefix}{sentence_to_use}{suffix}'
            
            return {
                "title": final_title,
                "description": description,
            }
        
        # Total fallback: use the raw title line as-is
        final_title = title if len(title) <= 100 else title[:97] + "..."
        return {
            "title": final_title,
            "description": description,
        }
