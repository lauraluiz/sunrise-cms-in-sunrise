import com.commercetools.sunrise.categorytree.CachedCategoryTreeProvider;
import com.commercetools.sunrise.categorytree.CategoryTreeConfiguration;
import com.commercetools.sunrise.categorytree.NavigationCategoryTree;
import com.commercetools.sunrise.categorytree.NewCategoryTree;
import com.commercetools.sunrise.cms.CmsService;
import com.commercetools.sunrise.cms.contentful.ContentfulCmsService;
import com.commercetools.sunrise.framework.controllers.metrics.SimpleMetricsSphereClientProvider;
import com.commercetools.sunrise.framework.injection.RequestScoped;
import com.commercetools.sunrise.framework.localization.CountryFromSessionProvider;
import com.commercetools.sunrise.framework.localization.CurrencyFromCountryProvider;
import com.commercetools.sunrise.framework.localization.LocaleFromUrlProvider;
import com.commercetools.sunrise.framework.template.cms.FileBasedCmsServiceProvider;
import com.commercetools.sunrise.framework.template.engine.HandlebarsTemplateEngineProvider;
import com.commercetools.sunrise.framework.template.engine.TemplateEngine;
import com.commercetools.sunrise.framework.template.i18n.ConfigurableI18nResolverProvider;
import com.commercetools.sunrise.framework.template.i18n.I18nResolver;
import com.commercetools.sunrise.framework.viewmodels.content.carts.MiniCartViewModelFactory;
import com.commercetools.sunrise.httpauth.HttpAuthentication;
import com.commercetools.sunrise.httpauth.basic.BasicAuthenticationProvider;
import com.commercetools.sunrise.productcatalog.productoverview.ProductListFinder;
import com.commercetools.sunrise.productcatalog.productoverview.ProductListFinderByCategoryWithMatchingVariants;
import com.commercetools.sunrise.search.facetedsearch.terms.viewmodels.AlphabeticallySortedTermFacetViewModelFactory;
import com.commercetools.sunrise.search.facetedsearch.terms.viewmodels.CustomSortedTermFacetViewModelFactory;
import com.commercetools.sunrise.search.facetedsearch.terms.viewmodels.TermFacetViewModelFactory;
import com.commercetools.sunrise.sessions.cart.TruncatedMiniCartViewModelFactory;
import com.commercetools.sunrise.sessions.customer.CustomerInSession;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.name.Names;
import com.neovisionaries.i18n.CountryCode;
import io.sphere.sdk.categories.CategoryTree;
import io.sphere.sdk.client.SphereClient;
import io.sphere.sdk.products.search.PriceSelection;
import io.sphere.sdk.producttypes.ProductType;
import io.sphere.sdk.producttypes.ProductTypeLocalRepository;
import io.sphere.sdk.producttypes.queries.ProductTypeQuery;
import play.Configuration;

import javax.inject.Singleton;
import javax.money.CurrencyUnit;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static io.sphere.sdk.client.SphereClientUtils.blockingWait;
import static io.sphere.sdk.queries.QueryExecutionUtils.queryAll;
import static java.util.Collections.emptyList;

/**
 * This class is a Guice module that tells Guice how to bind several
 * different types. This Guice module is created when the Play
 * application starts.
 *
 * Play will automatically use any class called `Module` that is in
 * the root package. You can create modules in other locations by
 * adding `play.modules.enabled` settings to the `application.conf`
 * configuration file.
 */
public class Module extends AbstractModule {

    @Override
    protected void configure() {
        // Binding for the client to connect commercetools
        bind(SphereClient.class)
                .toProvider(SimpleMetricsSphereClientProvider.class)
                .in(Singleton.class);

        // Binding for the HTTP Authentication
        bind(HttpAuthentication.class)
                .toProvider(BasicAuthenticationProvider.class)
                .in(Singleton.class);

        // Binding for category tree
        bind(CategoryTree.class).toProvider(CachedCategoryTreeProvider.class);

        // Binding for all template related, such as the engine, CMS and i18n
//        bind(CmsService.class)
//                .toProvider(FileBasedCmsServiceProvider.class)
//                .in(Singleton.class);
        bind(TemplateEngine.class)
                .toProvider(HandlebarsTemplateEngineProvider.class)
                .in(Singleton.class);
        bind(I18nResolver.class)
                .toProvider(ConfigurableI18nResolverProvider.class)
                .in(Singleton.class);

        // Bindings for all user context related
        bind(Locale.class)
                .toProvider(LocaleFromUrlProvider.class)
                .in(RequestScoped.class);
        bind(CountryCode.class)
                .toProvider(CountryFromSessionProvider.class)
                .in(RequestScoped.class);
        bind(CurrencyUnit.class)
                .toProvider(CurrencyFromCountryProvider.class)
                .in(RequestScoped.class);

        // Bindings for the configured faceted search mappers
        bind(TermFacetViewModelFactory.class)
                .annotatedWith(Names.named("alphabeticallySorted"))
                .to(AlphabeticallySortedTermFacetViewModelFactory.class)
                .in(RequestScoped.class);
        bind(TermFacetViewModelFactory.class)
                .annotatedWith(Names.named("customSorted"))
                .to(CustomSortedTermFacetViewModelFactory.class)
                .in(RequestScoped.class);

        // Binding to truncate mini cart to fit it into limited session space
        bind(MiniCartViewModelFactory.class).to(TruncatedMiniCartViewModelFactory.class);

        // Binding to enable matching variants on listing products
        // IMPORTANT: comment the following line if your project does not require this functionality, leaving it on can severely affect performance
        bind(ProductListFinder.class).to(ProductListFinderByCategoryWithMatchingVariants.class);

        // Provide here your own bindings
    }

    @Provides
    @Singleton
    public CmsService provideCmsService(final Configuration configuration) {
        final String spaceId = configuration.getString("contentful.spaceId");
        final String accessToken = configuration.getString("contentful.accessToken");
        final String productContentTypeId = "page";
        final String pageSlugFieldId = "slug";

        return ContentfulCmsService.of(spaceId, accessToken, productContentTypeId, pageSlugFieldId, ForkJoinPool.commonPool());
    }

    @Provides
    @RequestScoped
    @NavigationCategoryTree
    private CategoryTree provideNavigationCategoryTree(final CategoryTreeConfiguration configuration, final CategoryTree categoryTree) {
        return configuration.navigationExternalId()
                .flatMap(categoryTree::findByExternalId)
                .map(categoryTree::findChildren)
                .map(categoryTree::getSubtree)
                .orElse(categoryTree);
    }

    @Provides
    @RequestScoped
    @NewCategoryTree
    private CategoryTree provideNewCategoryTree(final CategoryTreeConfiguration configuration, final CategoryTree categoryTree) {
        return configuration.newExtId()
                .flatMap(categoryTree::findByExternalId)
                .map(categoryTree::findChildren)
                .map(categoryTree::getSubtree)
                .orElseGet(() -> CategoryTree.of(emptyList()));
    }

    @Provides
    @Singleton
    private ProductTypeLocalRepository fetchProductTypeLocalRepository(final SphereClient sphereClient) {
        final ProductTypeQuery query = ProductTypeQuery.of();
        final List<ProductType> productTypes = blockingWait(queryAll(sphereClient, query), 1, TimeUnit.MINUTES);
        return ProductTypeLocalRepository.of(productTypes);
    }

    @Provides
    @RequestScoped
    public DateTimeFormatter dateTimeFormatter(final Locale locale) {
        return DateTimeFormatter.ofPattern("MMM d, yyyy", locale);
    }

    @Provides
    @RequestScoped
    public PriceSelection providePriceSelection(final CurrencyUnit currency, final CountryCode country,
                                                final CustomerInSession customerInSession) {
        return PriceSelection.of(currency)
                .withPriceCountry(country)
                .withPriceCustomerGroupId(customerInSession.findCustomerGroupId().orElse(null));
    }
}
