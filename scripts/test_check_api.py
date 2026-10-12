import importlib.util
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
spec = importlib.util.spec_from_file_location('check_api', Path(__file__).with_name('check-api.py'))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def surface(members=(), declaration='public interface example.Provider {', directives=('exports example',)):
    return {'module': {'module': list(directives), 'classes': {'example.Provider': {
        'declaration': declaration, 'members': list(members)}}}}


class CompatibilityTest(unittest.TestCase):
    def test_removal_and_descriptor_change_fail(self):
        old = surface(['public abstract int get();\ndescriptor: ()I'])
        self.assertTrue(checker.incompatibilities(old, surface()))
        self.assertTrue(checker.incompatibilities(old, surface(['public abstract long get();\ndescriptor: ()J'])))

    def test_new_abstract_obligation_fails(self):
        self.assertTrue(checker.incompatibilities(surface(), surface(['public abstract void run();\ndescriptor: ()V'])))

    def test_concrete_addition_passes(self):
        self.assertFalse(checker.incompatibilities(surface(), surface(['public default void run();\ndescriptor: ()V'])))

    def test_export_and_type_changes_fail(self):
        self.assertTrue(checker.incompatibilities(surface(), surface(directives=())))
        self.assertTrue(checker.incompatibilities(surface(), surface(declaration='public class example.Provider {')))
        self.assertTrue(checker.incompatibilities(surface(), {}))


if __name__ == '__main__':
    unittest.main()
