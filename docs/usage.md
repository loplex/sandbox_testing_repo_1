# Using Inspect Code with Filters

Choose **Code | Inspect Code with Filters…**, or the same item in the Project view's context menu.\
In IntelliJ IDEA it is under **Analyze** there, beside **Inspect Code…**.

The dialog is the one **Inspect Code…** shows, with a **Report Only** group added under the inspection profile.\
Each row sums up one filter; **Choose…** opens it.

| Row                           | Reports only                                                    |
|-------------------------------|-----------------------------------------------------------------|
| [Severities](#severities)     | the inspections of the severities chosen                        |
| [Groups](#groups)             | the inspections in the groups chosen                            |
| [Inspections](#inspections)   | the inspections that are cleanup, modified or batch-mode ones   |
| [Problems](#problems)         | the problems on changed lines, with a quick fix, or with a text |

A problem is reported only when its inspection passes the first three rows, and the problem itself the fourth.

A change in a popup applies as it is made:

- **OK**, **Enter**, or a click outside the popup, closes it and keeps the changes.
- **Esc** closes it and goes back to what was chosen when it opened.

After the filters:

- [What is remembered](#what-is-remembered) — between runs, and on **Rerun**.
- [Known issue in IntelliJ IDEA 2025.3](#known-issue-in-intellij-idea-20253-reporting-only-one-of-grazies-two-checks-fails-the-run)
  — Grazie, and the warning in the dialog.

## Severities

- **This and higher** reports one severity and every severity above it.
- **Selected** reports the severities ticked one by one.
- **Edit Severities…** opens the IDE's editor of severities, where their order can be changed and custom ones added.

### Caveat: the severity is the one the inspection is configured with, not the one a problem is shown with

The filter goes by the severity an inspection has in the profile, as **Group by Severity** in the results does.\
An inspection configured as a warning is left out with the warnings, even when some of its problems are highlighted
as weak warnings, and the other way round.

Where the profile gives an inspection another severity in some scope, it is reported only in the scopes whose
severity is chosen.

## Groups

The groups are those of the inspection settings, as a tree with a search field.

- Ticking a group ticks every group under it.
- **Check All** and **Uncheck All** act on the groups the search has found, or on all of them when there is no search.
- **Revert** goes back to what was chosen when the popup opened, and leaves it open; it shows once something has changed.
- Only groups with at least one inspection switched on in the chosen profile are offered.

## Inspections

These are the filters of the inspection settings' own filter menu:

- **Cleanup inspections only**: those whose fixes **Code Cleanup** applies.
- **Modified in the profile only**: those whose settings differ from the defaults: switched on or off, severity,
  scopes or options.
- **Batch-mode inspections only**: those that run only in **Inspect Code**, and never highlight code in the editor.

## Problems

These filter the problems themselves, one by one, rather than the inspections that report them:

- **On lines changed since the last commit**: those on a line added or changed since.
  See [what counts as a changed line](#what-counts-as-a-changed-line).
- **With a quick fix only**: those that come with a fix.
- **Message contains**: those whose message, as the results show it, contains the text, ignoring case.
  Leave it empty for any message.

With more than one, a problem has to pass them all.\
**Unused declaration** also shows elements with no problem in them, which the filters judge by
[what is shown of them](#how-elements-nothing-uses-are-filtered).

### What counts as a changed line

The lines are those that differ from the last commit, as the gutter of the editor marks them:

- A line added or changed counts, whether its change is staged or not.
- A line only deleted does not: it is no longer there for a problem to be on.
- A file added since the last commit counts as a whole.
- A file the VCS does not track has no changed line, and neither has a file outside any VCS.

A problem is on a changed line when the code it marks meets one.

The lines are found anew as each run starts, **Rerun** included, so they are those of the code the run inspects.

### Caveat: only the reporting is narrowed, not what is inspected

The inspections still run over the whole scope, so that global ones, such as **Unused declaration**, still see every
use of what they report on.\
Local inspections, which check one file at a time, skip the files with no changed line.

### How elements nothing uses are filtered

**Unused declaration** shows a class, method or field nothing uses with no problem reported in it.\
What the results say of it, such as "Method is never used.", is made up only as it is shown.\
The filters judge such an element by what is shown of it instead:

- **On lines changed**: its name is on a changed line, as the editor marks the name.
  A changed annotation or body line alone does not count.
- **With a quick fix only**: it always passes.
  The results offer **Safe delete**, **Comment out** and **Add as Entry Point** for every such element, until one of
  them is applied.
- **Message contains**: the description in its preview contains the text, ignoring case.
  The node in the tree shows only the element's name; an export of the results gives the same description.

An element left out is left out of an export of the results too.\
The parameters and variables nothing uses are problems like any other, and are filtered as such.

## What is remembered

- The choices are remembered per project.
- **Rerun** in the results repeats the run with the filter it had, even when the choice has changed since.
  Only the changed lines are found anew.

## Known issue in IntelliJ IDEA 2025.3: reporting only one of Grazie's two checks fails the run

In IntelliJ IDEA 2025.3, Grazie checks grammar and style with one inspection.\
It reports problems of each check switched on in the project's profile, even of one the filter left out.

The run then fails on those problems:

- the IDE reports an error;
- other results in the same files are missing.

The dialog shows a warning when the choice would do this.\
To avoid it, report both Grazie inspections, or neither.\
The bug is fixed in IntelliJ IDEA 2026.1.
