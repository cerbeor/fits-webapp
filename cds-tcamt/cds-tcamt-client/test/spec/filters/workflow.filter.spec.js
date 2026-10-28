'use strict';

// Covers the predicate behind switchWorkflow(): whether a test case carrying a
// given workflow tag survives the active side-panel filter.
describe('workflow filter', function () {
    var workflowFilter;

    beforeEach(module('tcl'));
    beforeEach(inject(function ($filter) {
        workflowFilter = $filter('workflow');
    }));

    function visible(tag, activeFilter) {
        return workflowFilter([{ workflowTag: tag }], activeFilter).length > 0;
    }

    it("keeps every tag visible under 'ALL'", function () {
        expect(visible('FINAL', 'ALL')).toBe(true);
        expect(visible('DRAFT', 'ALL')).toBe(true);
        expect(visible('', 'ALL')).toBe(true);
    });

    it('keeps a case visible when its tag matches the filter', function () {
        expect(visible('FINAL', 'FINAL')).toBe(true);
    });

    it('hides a case whose tag differs from the filter', function () {
        expect(visible('DRAFT', 'FINAL')).toBe(false);
    });

    it("treats an untagged case as matching the 'Undefined' filter", function () {
        expect(visible('', '')).toBe(true);
        expect(visible(undefined, '')).toBe(true);
    });

    it("hides an untagged case under a concrete filter", function () {
        expect(visible('', 'FINAL')).toBe(false);
    });
});
