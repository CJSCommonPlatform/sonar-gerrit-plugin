package pl.touk.sonar;

import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.*;
import org.sonar.api.config.Settings;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.Resource;
import org.sonar.api.resources.ResourceUtils;
import org.sonar.api.rules.Violation;
import pl.touk.sonar.gerrit.GerritFacade;
import pl.touk.sonar.gerrit.ReviewComment;
import pl.touk.sonar.gerrit.ReviewInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

//http://sonarqube.15.x6.nabble.com/sonar-dev-Decorator-executed-a-lot-of-times-td5011536.html
@InstantiationStrategy(InstantiationStrategy.PER_BATCH)
public class GerritDecorator implements Decorator, PostJob {
    private final static Logger LOG = LoggerFactory.getLogger(GerritDecorator.class);
    private static final String COMMENT_FORMAT = "[%s] Severity: %s, Message: %s";
    private Review review;
    private GerritFacade gerritFacade;
    //Sonar's long name to Gerrit original file name map.
    private Map<String, String> gerritModifiedFiles;
    private ReviewInput reviewInput = new ReviewInput();

    public GerritDecorator(Settings settings) {
        this.review = new Review();
        review.setGerritHost(settings.getString(PropertyKey.GERRIT_HOST));
        review.setGerritHttpPort(settings.getInt(PropertyKey.GERRIT_HTTP_PORT));
        review.setGerritHttpUsername(settings.getString(PropertyKey.GERRIT_HTTP_USERNAME));
        review.setGerritHttpPassword(settings.getString(PropertyKey.GERRIT_HTTP_PASSWORD));
        review.setGerritProjectName(settings.getString(PropertyKey.GERRIT_PROJECT));
        review.setGerritChangeId(settings.getString(PropertyKey.GERRIT_CHANGE_ID));
        review.setGerritRevisionId(settings.getString(PropertyKey.GERRIT_REVISION_ID));
        review.assertGerritConfiguration();
        gerritFacade = new GerritFacade(review.getGerritHost(), review.getGerritHttpPort(), review.getGerritHttpUsername(), review.getGerritHttpPassword());
    }

    @Override
    public void decorate(Resource resource, DecoratorContext context) {
        if (!ResourceUtils.isFile(resource)) {
            return;
        }
        LOG.info("Processing resource qualifier {}, long name {}, name {}", new Object[] {resource.getScope(), resource.getLongName(), resource.getName()});
        LOG.info("Decorate on resource {} with this {}", resource, this);
        if (!review.isGerritConfigurationValid()) {
            return;
        }
        try {
            assertOrFetchGerritModifiedFiles();
            LOG.info("Has violations: {}", context.getViolations());
            processFileResource(resource, context);
        } catch (GerritPluginException e) {
            LOG.error("Error processing Gerrit Plugin decorator", e);
        }
    }

    @Override
    public void executeOn(Project project, SensorContext context) {
        if (!review.isGerritConfigurationValid()) {
            LOG.info("Analysis has finished. Not sending results to Gerrit, because configuration is not valid.");
            return;
        }
        LOG.info("Analysis has finished. Sending results to Gerrit.");
        try {
            reviewInput.setLabelToPlusOne();
            gerritFacade.setReview(review.getGerritChangeId(), review.getGerritRevisionId(), reviewInput);
        } catch (GerritPluginException e) {
            LOG.error("Error sending review to Gerrit", e);
        }
    }

    protected void processFileResource(@NotNull Resource resource, @NotNull DecoratorContext context) {
        if (gerritModifiedFiles.containsKey(resource.getLongName())) {
            LOG.info("File in Sonar {} matches file in Gerrit {}", resource.getLongName());
            List<ReviewComment> comments = new ArrayList<ReviewComment>();
            for (Violation violation : context.getViolations()) {
                LOG.info("Violation found: {}", violation.toString());
                comments.add(violationToComment(violation));
            }
            reviewInput.comments.put(gerritModifiedFiles.get(resource.getLongName()), comments);
        }
    }

    protected ReviewComment violationToComment(Violation violation) {
        ReviewComment result = new ReviewComment();
        result.line = violation.getLineId();
        result.message = String.format(COMMENT_FORMAT, StringUtils.capitalize(violation.getRule().getRepositoryKey()), violation.getSeverity().toString(), violation.getMessage());
        return result;
    }

    protected void assertOrFetchGerritModifiedFiles() throws GerritPluginException {
        if (gerritModifiedFiles != null) {
            return;
        }
        gerritModifiedFiles = gerritFacade.listFiles(review.getGerritChangeId(), review.getGerritRevisionId());
    }

    @DependsUpon
    public String dependsOnViolations() {
        return DecoratorBarriers.END_OF_VIOLATIONS_GENERATION;
    }

    @Override
    public boolean shouldExecuteOnProject(Project project) {
        return true;
    }


}
