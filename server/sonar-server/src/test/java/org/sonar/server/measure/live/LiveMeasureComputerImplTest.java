/*
 * SonarQube
 * Copyright (C) 2009-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.measure.live;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mockito;
import org.sonar.api.config.PropertyDefinitions;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.api.measures.Metric;
import org.sonar.api.resources.Qualifiers;
import org.sonar.core.config.CorePropertyDefinitions;
import org.sonar.db.DbTester;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentTesting;
import org.sonar.db.measure.LiveMeasureDto;
import org.sonar.db.metric.MetricDto;
import org.sonar.server.computation.task.projectanalysis.qualitymodel.Rating;
import org.sonar.server.es.ProjectIndexer;
import org.sonar.server.es.TestProjectIndexers;
import org.sonar.server.qualitygate.changeevent.QGChangeEvent;
import org.sonar.server.settings.ProjectConfigurationLoader;
import org.sonar.server.settings.TestProjectConfigurationLoader;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.sonar.api.resources.Qualifiers.ORDERED_BOTTOM_UP;

public class LiveMeasureComputerImplTest {

  @Rule
  public DbTester db = DbTester.create();
  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  private TestProjectIndexers projectIndexer = new TestProjectIndexers();
  private MetricDto intMetric;
  private MetricDto ratingMetric;
  private ComponentDto project;
  private ComponentDto dir;
  private ComponentDto file1;
  private ComponentDto file2;

  @Before
  public void setUp() throws Exception {
    intMetric = db.measures().insertMetric(m -> m.setValueType(Metric.ValueType.INT.name()));
    ratingMetric = db.measures().insertMetric(m -> m.setValueType(Metric.ValueType.RATING.name()));
    project = db.components().insertMainBranch();
    dir = db.components().insertComponent(ComponentTesting.newDirectory(project, "src/main/java"));
    file1 = db.components().insertComponent(ComponentTesting.newFileDto(project, dir));
    file2 = db.components().insertComponent(ComponentTesting.newFileDto(project, dir));
  }

  @Test
  public void compute_and_insert_measures_if_they_dont_exist_yet() {
    markProjectAsAnalyzed(project);

    List<QGChangeEvent> result = run(asList(file1, file2), newQualifierBasedIntFormula(), newRatingConstantFormula(Rating.C));

    // 2 measures per component have been created
    // Numeric value depends on qualifier (see newQualifierBasedIntFormula())
    assertThat(db.countRowsOfTable(db.getSession(), "live_measures")).isEqualTo(8);
    assertThatIntMeasureHasValue(file1, ORDERED_BOTTOM_UP.indexOf(Qualifiers.FILE));
    assertThatRatingMeasureHasValue(file1, Rating.C);
    assertThatIntMeasureHasValue(file2, ORDERED_BOTTOM_UP.indexOf(Qualifiers.FILE));
    assertThatRatingMeasureHasValue(file2, Rating.C);
    assertThatIntMeasureHasValue(dir, ORDERED_BOTTOM_UP.indexOf(Qualifiers.DIRECTORY));
    assertThatRatingMeasureHasValue(dir, Rating.C);
    assertThatIntMeasureHasValue(project, ORDERED_BOTTOM_UP.indexOf(Qualifiers.PROJECT));
    assertThatRatingMeasureHasValue(project, Rating.C);
    assertThatProjectChanged(result, project);
  }

  @Test
  public void compute_and_update_measures_if_they_already_exist() {
    markProjectAsAnalyzed(project);
    db.measures().insertLiveMeasure(project, intMetric, m -> m.setValue(42.0));
    db.measures().insertLiveMeasure(dir, intMetric, m -> m.setValue(42.0));
    db.measures().insertLiveMeasure(file1, intMetric, m -> m.setValue(42.0));
    db.measures().insertLiveMeasure(file2, intMetric, m -> m.setValue(42.0));

    // generates values 1, 2, 3
    List<QGChangeEvent> result = run(file1, newQualifierBasedIntFormula());

    assertThat(db.countRowsOfTable(db.getSession(), "live_measures")).isEqualTo(4);
    assertThatProjectChanged(result, project);

    // Numeric value depends on qualifier (see newQualifierBasedIntFormula())
    assertThatIntMeasureHasValue(file1, ORDERED_BOTTOM_UP.indexOf(Qualifiers.FILE));
    assertThatIntMeasureHasValue(dir, ORDERED_BOTTOM_UP.indexOf(Qualifiers.DIRECTORY));
    assertThatIntMeasureHasValue(project, ORDERED_BOTTOM_UP.indexOf(Qualifiers.PROJECT));
    // untouched
    assertThatIntMeasureHasValue(file2, 42.0);
  }

  @Test
  public void variation_is_refreshed_when_int_value_is_changed() {
    markProjectAsAnalyzed(project);
    // value is:
    // 42 on last analysis
    // 42-12=30 on beginning of leak period
    db.measures().insertLiveMeasure(project, intMetric, m -> m.setValue(42.0).setVariation(12.0));

    // new value is 44, so variation on leak period is 44-30=14
    List<QGChangeEvent> result = run(file1, newIntConstantFormula(44.0));

    LiveMeasureDto measure = assertThatIntMeasureHasValue(project, 44.0);
    assertThat(measure.getVariation()).isEqualTo(14.0);
    assertThatProjectChanged(result, project);
  }

  @Test
  public void variation_is_refreshed_when_rating_value_is_changed() {
    markProjectAsAnalyzed(project);
    // value is:
    // B on last analysis
    // D on beginning of leak period --> variation is -2
    db.measures().insertLiveMeasure(project, ratingMetric, m -> m.setValue((double) Rating.B.getIndex()).setData("B").setVariation(-2.0));

    // new value is C, so variation on leak period is D to C = -1
    List<QGChangeEvent> result = run(file1, newRatingConstantFormula(Rating.C));

    LiveMeasureDto measure = assertThatRatingMeasureHasValue(project, Rating.C);
    assertThat(measure.getVariation()).isEqualTo(-1.0);
    assertThatProjectChanged(result, project);
  }

  @Test
  public void variation_does_not_change_if_rating_value_does_not_change() {
    markProjectAsAnalyzed(project);
    // value is:
    // B on last analysis
    // D on beginning of leak period --> variation is -2
    db.measures().insertLiveMeasure(project, ratingMetric, m -> m.setValue((double) Rating.B.getIndex()).setData("B").setVariation(-2.0));

    // new value is still B, so variation on leak period is still -2
    List<QGChangeEvent> result = run(file1, newRatingConstantFormula(Rating.B));

    LiveMeasureDto measure = assertThatRatingMeasureHasValue(project, Rating.B);
    assertThat(measure.getVariation()).isEqualTo(-2.0);
    assertThatProjectChanged(result, project);
  }

  @Test
  public void refresh_leak_measures() {
    markProjectAsAnalyzed(project);
    db.measures().insertLiveMeasure(project, intMetric, m -> m.setVariation(42.0).setValue(null));
    db.measures().insertLiveMeasure(project, ratingMetric, m -> m.setVariation((double)Rating.E.getIndex()));
    db.measures().insertLiveMeasure(dir, intMetric, m -> m.setVariation(42.0).setValue(null));
    db.measures().insertLiveMeasure(dir, ratingMetric, m -> m.setVariation((double)Rating.D.getIndex()));
    db.measures().insertLiveMeasure(file1, intMetric, m -> m.setVariation(42.0).setValue(null));
    db.measures().insertLiveMeasure(file1, ratingMetric, m -> m.setVariation((double)Rating.C.getIndex()));

    // generates values 1, 2, 3 on leak measures
    List<QGChangeEvent> result = run(file1, newQualifierBasedIntLeakFormula(), newRatingLeakFormula(Rating.B));

    assertThat(db.countRowsOfTable(db.getSession(), "live_measures")).isEqualTo(6);

    // Numeric value depends on qualifier (see newQualifierBasedIntLeakFormula())
    assertThatIntMeasureHasLeakValue(file1, ORDERED_BOTTOM_UP.indexOf(Qualifiers.FILE));
    assertThatRatingMeasureHasLeakValue(file1, Rating.B);
    assertThatIntMeasureHasLeakValue(dir, ORDERED_BOTTOM_UP.indexOf(Qualifiers.DIRECTORY));
    assertThatRatingMeasureHasLeakValue(dir, Rating.B);
    assertThatIntMeasureHasLeakValue(project, ORDERED_BOTTOM_UP.indexOf(Qualifiers.PROJECT));
    assertThatRatingMeasureHasLeakValue(project, Rating.B);
    assertThatProjectChanged(result, project);
  }

  @Test
  public void do_nothing_if_project_has_not_being_analyzed() {
    // project has no snapshots
    List<QGChangeEvent> result = run(file1, newIncrementalFormula());

    assertThat(db.countRowsOfTable(db.getSession(), "live_measures")).isEqualTo(0);
    assertThatProjectNotChanged(result, project);
  }

  @Test
  public void do_nothing_if_input_components_are_empty() {
    List<QGChangeEvent> result = run(emptyList(), newIncrementalFormula());

    assertThat(db.countRowsOfTable(db.getSession(), "live_measures")).isEqualTo(0);
    assertThatProjectNotChanged(result, project);
  }

  @Test
  public void refresh_multiple_projects_at_the_same_time() {
    markProjectAsAnalyzed(project);
    ComponentDto project2 = db.components().insertMainBranch();
    ComponentDto fileInProject2 = db.components().insertComponent(ComponentTesting.newFileDto(project2));
    markProjectAsAnalyzed(project2);

    List<QGChangeEvent> result = run(asList(file1, fileInProject2), newQualifierBasedIntFormula());

    // generated values depend on position of qualifier in Qualifiers.ORDERED_BOTTOM_UP (see formula)
    assertThatIntMeasureHasValue(file1, 0);
    assertThatIntMeasureHasValue(dir, 2);
    assertThatIntMeasureHasValue(project, 4);
    assertThatIntMeasureHasValue(fileInProject2, 0);
    assertThatIntMeasureHasValue(project2, 4);

    // no other measures generated
    assertThat(db.countRowsOfTable(db.getSession(), "live_measures")).isEqualTo(5);
    assertThatProjectChanged(result, project, project2);
  }

  @Test
  public void refresh_multiple_branches_at_the_same_time() {
    // FIXME
  }

  @Test
  public void compute_quality_gate_status() {
    // FIXME
  }

  @Test
  public void exception_describes_context_when_a_formula_fails() {
    markProjectAsAnalyzed(project);
    Metric metric = new Metric.Builder(intMetric.getKey(), intMetric.getShortName(), Metric.ValueType.valueOf(intMetric.getValueType())).create();

    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("Fail to compute " + metric.getKey() + " on " + project.getDbKey());

    run(project, new IssueMetricFormula(metric, false, (context, issueCounter) -> {
      throw new NullPointerException("BOOM");
    }));
  }

  private List<QGChangeEvent> run(ComponentDto component, IssueMetricFormula... formulas) {
    return run(singleton(component), formulas);
  }

  private List<QGChangeEvent> run(Collection<ComponentDto> components, IssueMetricFormula... formulas) {
    IssueMetricFormulaFactory formulaFactory = new TestIssueMetricFormulaFactory(asList(formulas));

    LiveQualityGateComputer qGateComputer = mock(LiveQualityGateComputer.class, Mockito.RETURNS_DEEP_STUBS);
    MapSettings settings = new MapSettings(new PropertyDefinitions(CorePropertyDefinitions.all()));
    ProjectConfigurationLoader configurationLoader = new TestProjectConfigurationLoader(settings.asConfig());

    LiveMeasureComputerImpl underTest = new LiveMeasureComputerImpl(db.getDbClient(), formulaFactory, qGateComputer, configurationLoader, projectIndexer);

    return underTest.refresh(db.getSession(), components);
  }

  private void markProjectAsAnalyzed(ComponentDto p) {
    assertThat(p.qualifier()).isEqualTo(Qualifiers.PROJECT);
    db.components().insertSnapshot(p, s -> s.setPeriodDate(1_490_000_000L));
  }

  private LiveMeasureDto assertThatIntMeasureHasValue(ComponentDto component, double expectedValue) {
    LiveMeasureDto measure = db.getDbClient().liveMeasureDao().selectMeasure(db.getSession(), component.uuid(), intMetric.getKey()).get();
    assertThat(measure.getComponentUuid()).isEqualTo(component.uuid());
    assertThat(measure.getProjectUuid()).isEqualTo(component.projectUuid());
    assertThat(measure.getMetricId()).isEqualTo(intMetric.getId());
    assertThat(measure.getValue()).isEqualTo(expectedValue);
    return measure;
  }

  private LiveMeasureDto assertThatRatingMeasureHasValue(ComponentDto component, Rating expectedRating) {
    LiveMeasureDto measure = db.getDbClient().liveMeasureDao().selectMeasure(db.getSession(), component.uuid(), ratingMetric.getKey()).get();
    assertThat(measure.getComponentUuid()).isEqualTo(component.uuid());
    assertThat(measure.getProjectUuid()).isEqualTo(component.projectUuid());
    assertThat(measure.getMetricId()).isEqualTo(ratingMetric.getId());
    assertThat(measure.getValue()).isEqualTo(expectedRating.getIndex());
    assertThat(measure.getDataAsString()).isEqualTo(expectedRating.name());
    return measure;
  }

  private void assertThatIntMeasureHasLeakValue(ComponentDto component, double expectedValue) {
    LiveMeasureDto measure = db.getDbClient().liveMeasureDao().selectMeasure(db.getSession(), component.uuid(), intMetric.getKey()).get();
    assertThat(measure.getComponentUuid()).isEqualTo(component.uuid());
    assertThat(measure.getProjectUuid()).isEqualTo(component.projectUuid());
    assertThat(measure.getMetricId()).isEqualTo(intMetric.getId());
    assertThat(measure.getValue()).isNull();
    assertThat(measure.getVariation()).isEqualTo(expectedValue);
  }

  private void assertThatRatingMeasureHasLeakValue(ComponentDto component, Rating expectedValue) {
    LiveMeasureDto measure = db.getDbClient().liveMeasureDao().selectMeasure(db.getSession(), component.uuid(), ratingMetric.getKey()).get();
    assertThat(measure.getComponentUuid()).isEqualTo(component.uuid());
    assertThat(measure.getProjectUuid()).isEqualTo(component.projectUuid());
    assertThat(measure.getMetricId()).isEqualTo(ratingMetric.getId());
    assertThat(measure.getVariation()).isEqualTo((double) expectedValue.getIndex());
  }

  private IssueMetricFormula newIncrementalFormula() {
    Metric metric = new Metric.Builder(intMetric.getKey(), intMetric.getShortName(), Metric.ValueType.valueOf(intMetric.getValueType())).create();
    AtomicInteger counter = new AtomicInteger();
    return new IssueMetricFormula(metric, false, (ctx, issues) -> {
      ctx.setValue((double) counter.incrementAndGet());
    });
  }

  private IssueMetricFormula newIntConstantFormula(double constant) {
    Metric metric = new Metric.Builder(intMetric.getKey(), intMetric.getShortName(), Metric.ValueType.valueOf(intMetric.getValueType())).create();
    return new IssueMetricFormula(metric, false, (ctx, issues) -> {
      ctx.setValue(constant);
    });
  }

  private IssueMetricFormula newRatingConstantFormula(Rating constant) {
    Metric metric = new Metric.Builder(ratingMetric.getKey(), ratingMetric.getShortName(), Metric.ValueType.valueOf(ratingMetric.getValueType())).create();
    return new IssueMetricFormula(metric, false, (ctx, issues) -> {
      ctx.setValue(constant);
    });
  }

  private IssueMetricFormula newIncrementalLeakFormula() {
    Metric metric = new Metric.Builder(intMetric.getKey(), intMetric.getShortName(), Metric.ValueType.valueOf(intMetric.getValueType())).create();
    AtomicInteger counter = new AtomicInteger();
    return new IssueMetricFormula(metric, true, (ctx, issues) -> {
      ctx.setLeakValue((double) counter.incrementAndGet());
    });
  }

  private IssueMetricFormula newRatingLeakFormula(Rating rating) {
    Metric metric = new Metric.Builder(ratingMetric.getKey(), ratingMetric.getShortName(), Metric.ValueType.valueOf(ratingMetric.getValueType())).create();
    return new IssueMetricFormula(metric, true, (ctx, issues) -> {
      ctx.setLeakValue(rating);
    });
  }

  private IssueMetricFormula newQualifierBasedIntFormula() {
    Metric metric = new Metric.Builder(intMetric.getKey(), intMetric.getShortName(), Metric.ValueType.valueOf(intMetric.getValueType())).create();
    return new IssueMetricFormula(metric, false, (ctx, issues) -> {
      ctx.setValue(ORDERED_BOTTOM_UP.indexOf(ctx.getComponent().qualifier()));
    });
  }

  private IssueMetricFormula newQualifierBasedIntLeakFormula() {
    Metric metric = new Metric.Builder(intMetric.getKey(), intMetric.getShortName(), Metric.ValueType.valueOf(intMetric.getValueType())).create();
    return new IssueMetricFormula(metric, true, (ctx, issues) -> {
      ctx.setLeakValue(ORDERED_BOTTOM_UP.indexOf(ctx.getComponent().qualifier()));
    });
  }

  private void assertThatProjectChanged(List<QGChangeEvent> events, ComponentDto... projects) {
    for (ComponentDto p : projects) {
      assertThat(projectIndexer.hasBeenCalled(p.uuid(), ProjectIndexer.Cause.MEASURE_CHANGE)).isTrue();
    }

    assertThat(events).extracting(e -> e.getProject().uuid())
      .containsExactlyInAnyOrder(Arrays.stream(projects).map(ComponentDto::uuid).toArray(String[]::new));
  }

  private void assertThatProjectNotChanged(List<QGChangeEvent> events, ComponentDto project) {
    assertThat(projectIndexer.hasBeenCalled(project.uuid(), ProjectIndexer.Cause.MEASURE_CHANGE)).isFalse();
    assertThat(events).hasSize(0);
  }
}
