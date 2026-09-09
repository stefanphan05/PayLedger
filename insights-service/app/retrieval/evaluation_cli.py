import json
from pathlib import Path

from app.main import ApplicationContext
from app.retrieval.retrieval_service import RetrievalService

class RetrievalEvaluator:
    """Scores retrieval against a fixed set of questions with known answers.

    This is the only way to tell whether a change to chunking or to the model
    improved retrieval or broke it. Without a score, judging it means squinting
    at output and guessing.
    """
    def __init__(
        self,
        retrieval_service: RetrievalService,
        questions_file_path: Path
    ):
        self.retrieval_service = retrieval_service
        self.questions_file_path = questions_file_path

    def run_evaluation(self) -> None:
        """Run every question and print a pass/fail line plus a final score."""
        test_cases = json.loads(self.questions_file_path.read_text())
        passed_count = 0

        for test_case in test_cases:
            question = test_case["q"]
            expected_source_fragment = test_case["expect"]

            retrieved_chunks = self.retrieval_service.retrieve_relevant_chunks(question)

            was_expected_source_retrieved = any(
                expected_source_fragment in chunk.source_ref
                for chunk in retrieved_chunks
            )
            passed_count += was_expected_source_retrieved

            top_result = (
                retrieved_chunks[0].source_ref if retrieved_chunks else "(nothing)"
            )
            if was_expected_source_retrieved:
                outcome = "PASS"
            else:
                outcome = "FAIL"
            print(f"{outcome}  {question[:50]:<52} -> {top_result}")

        print(f"\n{passed_count}/{len(test_cases)}")

def main() -> None:
    """Run from the insights-service directory:
        python -m app.retrieval.evaluation_cli
    """
    application_context = ApplicationContext()

    evaluator = RetrievalEvaluator(
        retrieval_service=application_context.retrieval_service,
        questions_file_path=Path(__file__).parent.parent.parent / "eval/questions.json",
    )
    evaluator.run_evaluation()

if __name__ == "__main__":
    main()