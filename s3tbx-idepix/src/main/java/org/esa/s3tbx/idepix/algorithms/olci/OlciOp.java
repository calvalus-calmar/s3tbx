package org.esa.s3tbx.idepix.algorithms.olci;

import org.esa.s3tbx.idepix.core.AlgorithmSelector;
import org.esa.s3tbx.idepix.core.IdepixConstants;
import org.esa.s3tbx.idepix.core.util.IdepixIO;
import org.esa.s3tbx.idepix.operators.BasisOp;
import org.esa.s3tbx.idepix.operators.IdepixProducts;
import org.esa.s3tbx.processor.rad2refl.Sensor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.VirtualBand;
import org.esa.snap.core.gpf.GPF;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * The Idepix pixel classification operator for OLCI products.
 * <p>
 * Initial implementation:
 * - pure neural net approach which uses MERIS heritage bands only
 * - no auxdata available yet (such as 'L2 auxdata' for MERIS)
 * <p>
 * Currently resulting limitations:
 * - no cloud shadow flag
 * - glint flag over water just taken from 'sun_glint_risk' in L1 'quality_flags' band
 * <p>
 * Advanced algorithm to be defined which makes use of more bands.
 *
 * @author olafd
 */
@OperatorMetadata(alias = "Idepix.Sentinel3.Olci",
        category = "Optical/Pre-Processing",
        version = "1.0",
        authors = "Olaf Danne",
        copyright = "(c) 2016 by Brockmann Consult",
        description = "Pixel identification and classification for OLCI.")
public class OlciOp extends BasisOp {
    @SourceProduct(alias = "sourceProduct",
            label = "OLCI L1b product",
            description = "The OLCI L1b source product.")
    private Product sourceProduct;

    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    private boolean outputRadiance;
    private boolean outputRad2Refl;

    @Parameter(description = "The list of radiance bands to write to target product.",
            label = "Select TOA radiances to write to the target product",
            valueSet = {
                    "Oa01_radiance", "Oa02_radiance", "Oa03_radiance", "Oa04_radiance", "Oa05_radiance",
                    "Oa06_radiance", "Oa07_radiance", "Oa08_radiance", "Oa09_radiance", "Oa10_radiance",
                    "Oa11_radiance", "Oa12_radiance", "Oa13_radiance", "Oa14_radiance", "Oa15_radiance",
                    "Oa16_radiance", "Oa17_radiance", "Oa18_radiance", "Oa19_radiance", "Oa20_radiance",
                    "Oa21_radiance"
            },
            defaultValue = "")
    String[] radianceBandsToCopy;

    @Parameter(description = "The list of reflectance bands to write to target product.",
            label = "Select TOA reflectances to write to the target product",
            valueSet = {
                    "Oa01_reflectance", "Oa02_reflectance", "Oa03_reflectance", "Oa04_reflectance", "Oa05_reflectance",
                    "Oa06_reflectance", "Oa07_reflectance", "Oa08_reflectance", "Oa09_reflectance", "Oa10_reflectance",
                    "Oa11_reflectance", "Oa12_reflectance", "Oa13_reflectance", "Oa14_reflectance", "Oa15_reflectance",
                    "Oa16_reflectance", "Oa17_reflectance", "Oa18_reflectance", "Oa19_reflectance", "Oa20_reflectance",
                    "Oa21_reflectance"
            },
//            defaultValue = "Oa08_reflectance,Oa10_reflectance")
            defaultValue = "")
    String[] reflBandsToCopy;

    @Parameter(defaultValue = "false",
            label = " Write NN value to the target product",
            description = " If applied, write NN value to the target product ")
    private boolean outputSchillerNNValue;

    @Parameter(defaultValue = "true", label = " Compute a cloud buffer")
    private boolean computeCloudBuffer;

    @Parameter(defaultValue = "false",
            label = " Compute additional 'o2_cloud' flag using O2 corrected band13 transmission (experimental option)",
            description = " Computes and writes an additional 'o2_cloud' flag using O2 corrected transmission at " +
                    "band 13 (experimental option, requires additional plugin and reflectance bands 2-8, 17, 21)")
    private boolean applyO2CorrectedTransmission;

    @Parameter(defaultValue = "v3", valueSet = {"v3", "v1"},
            label = " Version of O2 Correction processor (if applied)",
            description = "Version of O2 Correction processor (if applied)")
    private String o2CorrectionVersion;

    @Parameter(defaultValue = "2", interval = "[0,100]",
            description = "The width of a cloud 'safety buffer' around a pixel which was classified as cloudy.",
            label = "Width of cloud buffer (# of pixels)")
    private int cloudBufferWidth;

    @Parameter(defaultValue = "false",
            label = " Compute cloud top pressure (experimental option, time consuming)",
            description = " Compute cloud top pressure (time consuming, requires Python plugin based on CAWA). ")
    private boolean computeCtp;

    @Parameter(defaultValue = "false",
            label = " Compute a cloud shadow (experimental option, requires cloud top pressure)",
            description = " If applied, a cloud shadow is computed. " +
                    "This requires the cloud top pressure operator (Python plugin based on CAWA) to be installed. " +
                    "Still experimental. ")
    private boolean computeCloudShadow;

    @Parameter(defaultValue = "false",
            label = " Compute 'combined cloud' band  (experimental option, following DM/JM)",
            description = " Compute 'combined cloud' band  (experimental option, following DM/JM). " +
                    "Requires nn_value, reflectance bands 2-8 and 17! ")
    private boolean computeCombinedCloud;


    private Product classificationProduct;
    private Product postProcessingProduct;
    private Product combinedCloudProduct;

    private Product rad2reflProduct;
    private Product waterMaskProduct;

    private Map<String, Product> classificationInputProducts;
    private Map<String, Object> classificationParameters;
    private Product o2CorrProduct;
    private Product o2CloudProduct;

    @Override
    public void initialize() throws OperatorException {

        final boolean inputProductIsValid = IdepixIO.validateInputProduct(sourceProduct, AlgorithmSelector.OLCI);
        if (!inputProductIsValid) {
            throw new OperatorException(IdepixConstants.INPUT_INCONSISTENCY_ERROR_MESSAGE);
        }

        computeCtp |= computeCloudShadow;

        outputRadiance = radianceBandsToCopy != null && radianceBandsToCopy.length > 0;
        outputRad2Refl = reflBandsToCopy != null && reflBandsToCopy.length > 0;

        preProcess();

        setClassificationInputProducts();
        computeCloudProduct();

        Product olciIdepixProduct = classificationProduct;
        olciIdepixProduct.setName(sourceProduct.getName() + "_IDEPIX");
        olciIdepixProduct.setAutoGrouping("Oa*_radiance:Oa*_reflectance");

        ProductUtils.copyFlagBands(sourceProduct, olciIdepixProduct, true);

        if (computeCtp || computeCloudBuffer || computeCloudShadow) {
            if (computeCtp || computeCloudShadow) {
                copyBandsForCtp(olciIdepixProduct);
                Map<String, Product> ctpSourceProducts = new HashMap<>();
                ctpSourceProducts.put("l1b", olciIdepixProduct);
                Product ctpProduct = GPF.createProduct("py_olci_ctp_op", GPF.NO_PARAMS, ctpSourceProducts);
                ProductUtils.copyBand("ctp", ctpProduct, olciIdepixProduct, true);
                olciIdepixProduct.getBand("ctp").setUnit("hPa");
            }
            if (computeCloudBuffer || computeCloudShadow) {
                postProcess(olciIdepixProduct);
            }
        }

        if (computeCombinedCloud) {
            processCombinedCloud(olciIdepixProduct);
        }

        targetProduct = createTargetProduct(olciIdepixProduct);
        targetProduct.setAutoGrouping(olciIdepixProduct.getAutoGrouping());

        if (applyO2CorrectedTransmission) {
            if (o2CorrectionVersion.equals("v1")) {
                for (int i = 13; i <= 15; i++) {
                    ProductUtils.copyBand("radiance_" + i, o2CorrProduct, "Oa" + i + "_radiance_o2corr",
                                          targetProduct, true);
                    ProductUtils.copyBand("trans_" + i, o2CorrProduct, "Oa" + i + "_trans_o2corr",
                                          targetProduct, true);
                }
                ProductUtils.copyBand("o2_cloud", o2CloudProduct, targetProduct, true);
                ProductUtils.copyBand("trans13_baseline", o2CloudProduct, targetProduct, true);
                ProductUtils.copyBand("trans13_baseline_AMFcorr", o2CloudProduct, targetProduct, true);
                ProductUtils.copyBand("trans13_excess", o2CloudProduct, targetProduct, true);
            } else {
                ProductUtils.copyBand("trans_13", o2CorrProduct, targetProduct, true);
                ProductUtils.copyBand("press_13", o2CorrProduct, targetProduct, true);
                ProductUtils.copyBand("surface_13", o2CorrProduct, targetProduct, true);
                ProductUtils.copyBand("altitude", sourceProduct, targetProduct, true);
                addSurfacePressureBand();
                addCloudOverSnowBand();
            }
        }

        if (postProcessingProduct != null) {
            Band cloudFlagBand = targetProduct.getBand(IdepixConstants.CLASSIF_BAND_NAME);
            cloudFlagBand.setSourceImage(postProcessingProduct.getBand(IdepixConstants.CLASSIF_BAND_NAME).getSourceImage());
        }
        if (combinedCloudProduct != null) {
            ProductUtils.copyBand(OlciConstants.OLCI_COMBINED_CLOUD_BAND_NAME, combinedCloudProduct, targetProduct, true);
        }
    }

    private Product createTargetProduct(Product idepixProduct) {
        Product targetProduct = new Product(idepixProduct.getName(),
                                            idepixProduct.getProductType(),
                                            idepixProduct.getSceneRasterWidth(),
                                            idepixProduct.getSceneRasterHeight());

        ProductUtils.copyMetadata(idepixProduct, targetProduct);
        ProductUtils.copyGeoCoding(idepixProduct, targetProduct);
        ProductUtils.copyFlagCodings(idepixProduct, targetProduct);
        ProductUtils.copyFlagBands(idepixProduct, targetProduct, true);
        ProductUtils.copyMasks(idepixProduct, targetProduct);
        ProductUtils.copyTiePointGrids(idepixProduct, targetProduct);
        targetProduct.setStartTime(idepixProduct.getStartTime());
        targetProduct.setEndTime(idepixProduct.getEndTime());

        OlciUtils.setupOlciClassifBitmask(targetProduct);

        if (outputRadiance) {
            IdepixIO.addRadianceBands(sourceProduct, targetProduct, radianceBandsToCopy);
        }
        if (outputRad2Refl) {
            IdepixIO.addOlciRadiance2ReflectanceBands(rad2reflProduct, targetProduct, reflBandsToCopy);
        }

        if (outputSchillerNNValue) {
            ProductUtils.copyBand(IdepixConstants.NN_OUTPUT_BAND_NAME, idepixProduct, targetProduct, true);
        }

        if (computeCtp) {
            ProductUtils.copyBand(IdepixConstants.CTP_OUTPUT_BAND_NAME, idepixProduct, targetProduct, true);
        }

        return targetProduct;
    }


    private void preProcess() {
        rad2reflProduct = IdepixProducts.computeRadiance2ReflectanceProduct(sourceProduct, Sensor.OLCI);

        HashMap<String, Object> waterMaskParameters = new HashMap<>();
        waterMaskParameters.put("resolution", IdepixConstants.LAND_WATER_MASK_RESOLUTION);
        waterMaskParameters.put("subSamplingFactorX", IdepixConstants.OVERSAMPLING_FACTOR_X);
        waterMaskParameters.put("subSamplingFactorY", IdepixConstants.OVERSAMPLING_FACTOR_Y);
        waterMaskProduct = GPF.createProduct("LandWaterMask", waterMaskParameters, sourceProduct);

        if (applyO2CorrectedTransmission) {
            Map<String, Product> o2corrSourceProducts = new HashMap<>();
            o2corrSourceProducts.put("l1b", sourceProduct);
            final String o2CorrOpName = o2CorrectionVersion.equals("v1") ? "py_o2corr_op" : "py_o2corr_v3_op";
            o2CorrProduct = GPF.createProduct(o2CorrOpName, GPF.NO_PARAMS, o2corrSourceProducts);

            Map<String, Product> o2CloudSourceProducts = new HashMap<>();
            o2CloudSourceProducts.put("l1b", sourceProduct);
            o2CloudSourceProducts.put("o2", o2CorrProduct);
            o2CloudProduct = GPF.createProduct("Idepix.Olci.O2cloud", GPF.NO_PARAMS, o2CloudSourceProducts);
        }

    }

    private void setClassificationParameters() {
        classificationParameters = new HashMap<>();
        classificationParameters.put("copyAllTiePoints", true);
        classificationParameters.put("outputSchillerNNValue", outputSchillerNNValue);
    }

    private void computeCloudProduct() {
        setClassificationParameters();
        classificationProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(OlciClassificationOp.class),
                                                  classificationParameters, classificationInputProducts);
    }

    private void setClassificationInputProducts() {
        classificationInputProducts = new HashMap<>();
        classificationInputProducts.put("l1b", sourceProduct);
        classificationInputProducts.put("rhotoa", rad2reflProduct);
        classificationInputProducts.put("waterMask", waterMaskProduct);
    }

    private void postProcess(Product olciIdepixProduct) {
        HashMap<String, Product> input = new HashMap<>();
        input.put("l1b", sourceProduct);
        input.put("olciCloud", olciIdepixProduct);

        Map<String, Object> params = new HashMap<>();
        params.put("computeCloudBuffer", computeCloudBuffer);
        params.put("computeCloudShadow", computeCloudShadow);

        postProcessingProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(OlciPostProcessOp.class),
                                                  params, input);
    }

    private void processCombinedCloud(Product olciIdepixProduct) {
        HashMap<String, Product> input = new HashMap<>();
        input.put("rad2refl", rad2reflProduct);
        input.put("olciCloud", olciIdepixProduct);

        Map<String, Object> params = new HashMap<>();

        combinedCloudProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(OlciCombinedCloudOp.class),
                                                 params, input);
    }

    private void copyBandsForCtp(Product targetProduct) {
        IdepixIO.addCawaBands(sourceProduct, targetProduct);
    }

    private void addSurfacePressureBand() {
        String presExpr = "(1013.25 * exp(-altitude/8400))";
        final Band surfPresBand = new VirtualBand("surface_pressure",
                                                  ProductData.TYPE_FLOAT32,
                                                  targetProduct.getSceneRasterWidth(),
                                                  targetProduct.getSceneRasterHeight(),
                                                  presExpr);
        surfPresBand.setDescription("estimated sea level pressure (p0=1013.25hPa, hScale=8.4km)");
        surfPresBand.setNoDataValue(0);
        surfPresBand.setNoDataValueUsed(true);
        surfPresBand.setUnit("hPa");
        targetProduct.addBand(surfPresBand);
    }

    private void addCloudOverSnowBand() {
        String expr = "pixel_classif_flags.IDEPIX_LAND && Oa21_reflectance > 0.5 && surface_13 - trans_13 < 0.01";
        final Band surfPresBand = new VirtualBand("cloud_over_snow",
                                                  ProductData.TYPE_FLOAT32,
                                                  targetProduct.getSceneRasterWidth(),
                                                  targetProduct.getSceneRasterHeight(),
                                                  expr);
        surfPresBand.setDescription("Pixel identified as likely cloud over a snow/ice surface");
        surfPresBand.setNoDataValue(0);
        surfPresBand.setNoDataValueUsed(true);
        surfPresBand.setUnit("dl");
        targetProduct.addBand(surfPresBand);
    }

    /**
     * The Service Provider Interface (SPI) for the operator.
     * It provides operator meta-data and is a factory for new operator instances.
     */
    public static class Spi extends OperatorSpi {

        public Spi() {
            super(OlciOp.class);
        }
    }
}
