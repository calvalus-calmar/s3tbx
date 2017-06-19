package org.esa.s2tbx.fcc.intern;

/**
 * @author Razvan Dumitrascu
 * @since 5.0.6
 */
public class PixelSourceBands {
    private float meanValueB4Band;
    private float meanvalueB8Band;
    private float meanValueB11Band;
    private float standardDeviationValueB8Band;

    public PixelSourceBands(float valueB4Band, float valueB8Band, float valueB11Band, float valueB12Band){
        this.meanValueB4Band = valueB4Band;
        this.meanvalueB8Band = valueB8Band;
        this.meanValueB11Band = valueB11Band;
        this.standardDeviationValueB8Band = valueB12Band;
    }

    public float getMeanValueB4Band(){
        return this.meanValueB4Band;
    }

    public float getMeanValueB8Band(){
        return this.meanvalueB8Band;
    }

    public float getMeanValueB11Band(){
        return this.meanValueB11Band;
    }

    public float getStandardDeviationValueB8Band(){
        return this.standardDeviationValueB8Band;
    }
}
