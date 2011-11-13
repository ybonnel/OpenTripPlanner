/* This program is free software: you can redistribute it and/or
   modify it under the terms of the GNU Lesser General Public License
   as published by the Free Software Foundation, either version 3 of
   the License, or (at your option) any later version.a
   
   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with this program.  If not, see <http://www.gnu.org/licenses/>. 
*/

otp.namespace("otp.planner");

/**
  * otp/planner/Forms.js 
  * 
  * Purpose is to manage the Ext input forms for the trip planner.
  * 
  */
otp.planner.StaticForms = {

    FIELD_ANCHOR          : '95%',

    // external (config) objects
    routerId              : null,
    locale                : null,
    planner               : null,
    contextMenu           : null,
    poi                   : null,
    url                   : '/bordeaux-api-webapp/ws/plan',
    showWheelchairForm    : true,
    useOptionDependencies : false, // form options context dependent based on mode and optimize flag 
    fromToOverride        : null,  // over-ride me to get rid of From / To from with something else
    geocoder              : null,

    // forms & stores
    m_panel               : null,

    m_routerIdForm        : null,
    
    m_fromForm            : null,
    m_toForm              : null,
    m_toPlace             : null,
    m_fromPlace           : null,
    m_intermediatePlaces  : null,

    m_date                : null,
    m_time                : null,
    m_arriveByStore       : null,
    m_arriveByForm        : null,

    m_maxWalkDistanceStore: null,
    m_maxWalkDistanceForm : null,
    m_optimizeStore       : null,
    m_optimizeForm        : null,
    m_modeStore           : null,
    m_modeForm            : null,
    m_wheelchairForm      : null,
    m_optionsManager      : null,

    m_bikeTriangle          : null,
    m_bikeTriangleContainer : null,

    // buttons
    m_submitButton        : null,

    m_fromCoord           : otp.util.Constants.BLANK_LAT_LON,
    m_toCoord             : otp.util.Constants.BLANK_LAT_LON,

    m_xmlRespRecord       : null,

    // to enable masking of the from/to fields
    m_fromToFP            : null,

    THIS                  : null,


    /**
     * @constructor
     * @param {Object} config
     */
    initialize : function(config)
    {
        // step 1: bit of init (before configure override happens)
        otp.configure(this, config);
        this.routerId = config.routerId;
        this.THIS = this;
        otp.planner.StaticForms.THIS = this;

        // step 2: setup
        if(this.m_xmlRespRecord == null)
        this.m_xmlRespRecord  = new Ext.data.XmlReader({
                record: 'response',
                success: '@success'
            },
            ['date', 'time', 'from', 'to', 'locations', 'fromList', 'toList']
        );

        config.form = this;
        this.geocoder = new otp.planner.Geocoder(config); 
        this.makeMainPanel();

        // step 3: set the singleton & status stuff to this 
        otp.planner.StaticForms = this;
    },

    /**
     * 
     */
    getPanel : function()
    {
        return this.m_panel;
    },

    /** IMPORTANT FUNCTION: 
     *  will make sure the forms are not in a dirty state, and will trigger the geocoder (via onBlur event on the form)
     *  NOTE: add more forms here with intermediate places
     */
    blurForms : function()
    {
        try
        {
            this.m_fromForm.blur();
            this.m_toForm.blur();
        }
        catch(e)
        {}
    },

    /**
     * called when submitting the form
     * 
     * TODO: this whole thing needs a major refactoring -- 
     *  -- the geocoder code should be more object based vs. all these conditionals 
     *  -- things in general need to be reduced and cleaned up... 1500+ lines is a big hint
     */
    submit : function()
    {
        this.blurForms();

        if(this.isBusyGeocoding())
        {
            // if we are currently waiting for a geocoder response,
            // then let's wait until we get a response before we submit
            // the form
            setTimeout(this.submit.createDelegate(this), 250);
            return;
        }

        if (this.m_fromForm.getComboBox().activeError ||
            this.m_toForm.getComboBox().activeError) {
            Ext.Msg.show({
                title: this.locale.tripPlanner.geocoder.msg_title,
                msg:   this.locale.tripPlanner.geocoder.msg_content
            });
            setTimeout(function() { Ext.Msg.hide(); }, 3000);
            return;
        }

        // hide stuff that might be open
        this.collapseComboBoxes();
        this.hideErrorDialogs();

        otp.util.ExtUtils.setTripPlannerCookie();

        // get bike form data if we're using the triangle
        var triParams = {};
        if(this.m_bikeTriangle && this.m_optimizeForm.getValue() == "TRIANGLE")
            triParams = this.m_bikeTriangle.getFormData();

        this.m_panel.form.submit( {
            method  : 'GET',
            url     : this.url,
            waitMsg : this.locale.tripPlanner.labels.submitMsg,
            params  : triParams
        });

        // analytics
        otp.util.Analytics.gaEvent(otp.util.Analytics.OTP_TRIP_SUBMIT);
    },

    /**
     * pre submit does some necessary bookkeeping for the form. the real work
     * happens in the (overridden) submit method
     */
    preSubmit : function(form, action)
    {
        // step 1: save off the state of the from & to text forms (eg: remember input into these fields)
        this.m_fromForm.persist();
        this.m_toForm.persist();

         // step 2: hide stuff that might be open
        this.collapseComboBoxes();
        this.hideErrorDialogs();

        // step 3: fix up some of the form values before sending onto the trip planner web service
        form.setValues({
            fromPlace: this.getFrom(),
            toPlace:   this.getTo()
        });
    },

    /** */
    submitSuccess : function(form, action)
    {
        var result = this.planner.newTripPlan(action.response.responseXML, this.getFormData());
        if (!result)
        {
            this.tripRequestError(action.response.responseXML);
            return;
        }
        if(this.poi) this.poi.clearTrip();
        otp.util.Analytics.gaEvent(otp.util.Analytics.OTP_TRIP_SUCCESS);
    },

    /** */
    submitFailure : function(form, action)
    {
        this.m_submitButton.focus();
        this.tripRequestError(action.response.responseXML);
    },

    /** error handler */
    tripRequestError : function(xml)
    {
        var message  = null;

        // load xml to see what errors we have
        try
        {
            var err  = Ext.DomQuery.selectNode('error', xml);
            var code = Ext.DomQuery.selectValue('id', err);
            message  = Ext.DomQuery.selectValue('msg', err);
            if (!message && code)
            {
                try
                {
                    code = parseInt(code);
                }
                catch(e)
                {
                    code = 500;
                }
                message = this.locale.tripPlanner.msgcodes[code] || this.locale.tripPlanner.msgcodes[500];
            }
            otp.util.Analytics.gaEvent(otp.util.Analytics.OTP_TRIP_ERROR);
        } 
        catch(e) 
        {
            console.log("exception with somethingorother: " + e);
            if(message == null || message == '')
                message = this.locale.tripPlanner.error.deadMsg;
        }

        if(message != null && message.length > 0)
        {
            // show the error
            Ext.MessageBox.show({
                title:    this.locale.tripPlanner.error.title,
                msg:      message,
                buttons:  Ext.Msg.OK,
                icon:     Ext.MessageBox.ERROR,
                animEl:   'tp-error-message',
                minWidth: 170,
                maxWidth: 270
            });

            // kill the message box after 5 seconds
            setTimeout(function()
            {
                try {
                    Ext.MessageBox.hide();
                } catch(e) {}
            }, 5000);
        }
    },


    /**
     * 
     */
    clear : function()
    {
        this.collapseComboBoxes();
        this.hideErrorDialogs();
        this.clearFrom(true);
        this.clearTo(true);
        this.m_submitButton.enable();
        this.m_submitButton.focus();
    },

    /** */
    collapseComboBoxes : function()
    {
        try
        {
            this.m_fromForm.collapse();
            this.m_toForm.collapse();
        }
        catch(e)
        {
            console.log("Forms.collapseComboBox " + e);
        }
    },

    /** */
    hideErrorDialogs : function()
    {
        // hide the default message box
        try {Ext.MessageBox.hide();        } catch(e){}
        try {this.m_geoErrorPopup.close();  } catch(e){}
    },

    /** */
    focus : function()
    {
        this.THIS.planner.focus();
    },

    /**
     * set from form with either/both a string and X/Y
     * NOTE: x & y are reversed -- really are lat,lon
     *
     */
    setFrom : function(fString, lat, lon, moveMap, noPoi)
    {
        this.THIS.focus();
        if(lat && lat > -181.1 && lon && lon > -181.1) 
        {
            this.THIS.m_fromCoord = lat + ',' + lon;
            if(this.THIS.poi && !noPoi)
                this.THIS.poi.setFrom(lon, lat, fString, moveMap);

            otp.util.ExtUtils.formSetRawValue(this.THIS.m_fromForm, fString, this.THIS.m_fromCoord);
            this.THIS.m_fromForm.setLastValue();
        }
    },

    /**
     * set to form with either/both a string and X/Y
     * NOTE: x & y are reversed -- really are lat,lon
     */
    setTo : function(tString, lat, lon, moveMap, noPoi)
    {
        this.THIS.focus();
        if(lat && lat > -181.1 && lon && lon > -181.1) 
        {
            this.THIS.m_toCoord = lat + ',' + lon;
            if(this.THIS.poi && !noPoi)
                this.THIS.poi.setTo(lon, lat, tString, moveMap);

            otp.util.ExtUtils.formSetRawValue(this.m_toForm, tString, this.THIS.m_toCoord);
            this.THIS.m_toForm.setLastValue();
        }
    },


    /** will look in text forms first, then hidden form variable, then coordinate for value */
    getFrom : function()
    {
        return this.m_fromCoord ||
               this.m_fromForm ||
               this.m_fromPlace;
    },


    /** will look in text forms first, then hidden form variable, then coordinate for value */
    getTo : function()
    {
        return this.m_toCoord ||
               this.m_toForm ||
               this.m_toPlace;
    },


    /** a simple helper class to set data in a form ... note we also set a dirty flag on said form */
    setDirtyRawInput : function(p, f, d)
    {
        var retVal = false;

        if( p != null 
         && p !== true 
         && p != "true" 
         && p.match('Address, .*Stop ID') == null
        )
        {
            otp.util.ExtUtils.formSetRawValue(f, p, d, true);
            retVal = true;
        }

        return retVal;
    },

    /** */
    populate : function(params)
    {
        var forms = this;
        if(params)
        {
            this.clearFrom();
            this.clearTo();

            this.setDirtyRawInput(params.Orig,      forms.m_fromForm);
            this.setDirtyRawInput(params.Orig,      forms.m_fromPlace);
            this.setDirtyRawInput(params.Dest,      forms.m_toForm);
            this.setDirtyRawInput(params.Dest,      forms.m_toPlace);
            this.setDirtyRawInput(params.from,      forms.m_fromForm);
            this.setDirtyRawInput(params.from,      forms.m_fromPlace);
            this.setDirtyRawInput(params.to,        forms.m_toForm);
            this.setDirtyRawInput(params.to,        forms.m_toPlace);
            this.setDirtyRawInput(params.fromPlace, forms.m_fromForm);
            this.setDirtyRawInput(params.fromPlace, forms.m_fromPlace);
            this.setDirtyRawInput(params.toPlace,   forms.m_toForm);
            this.setDirtyRawInput(params.toPlace,   forms.m_toPlace);

            // triangleSafetyFactor=0.409&triangleSlopeFactor=0.0974&triangleTimeFactor=0.493
            if(this.m_bikeTriangle)
                this.m_bikeTriangle.setSHT(params.triangleSafetyFactor, params.triangleSlopeFactor, params.triangleTimeFactor);

            if (params.fromPlace && params.fromPlace.length > 2 && params.fromPlace.indexOf(",") > 0) {
                var lat = otp.util.ObjUtils.getLat(params.fromPlace);
                var lon = otp.util.ObjUtils.getLon(params.fromPlace);
                this.setFrom(params.fromPlace, lat, lon, false, false);
            }
            if (params.toPlace && params.toPlace.length > 2 && params.toPlace.indexOf(",") > 0) {
                var lat = otp.util.ObjUtils.getLat(params.toPlace);
                var lon = otp.util.ObjUtils.getLon(params.toPlace);
                this.setTo(params.toPlace, lat, lon, false, false);
            }

            if(params.fromCoord && params.fromCoord.indexOf('0.0') != 0)
                this.m_fromCoord = params.fromCoord;
            if(params.toCoord && params.toCoord.indexOf('0.0') != 0)
                this.m_toCoord = params.toCoord;

            // TODO - should find a way to make the xxx (String value, vs coded value) work properly in submit
            var time=false;
            var date=false;

            if(params.date)
            {
                forms.m_date.setRawValue(params.date);
                date = true;
            }
            else if(params.on)
            {
                forms.m_date.setRawValue(params.on);
                date = true;
            }

            // arrive by parameter 
            if(params.arrParam && (params.arrParam.indexOf("rive") > 0 || params.arrParam == "true"))
                forms.m_arriveByForm.setValue('true');
            if(params.arr && (params.arr.indexOf("rive") > 0 || params.arr == "true"))
                forms.m_arriveByForm.setValue('true');
            if(params.Arr && (params.Arr.indexOf("rive") > 0 || params.Arr == "true"))
                forms.m_arriveByForm.setValue('true');
            if(params.arrParam && (params.arrParam.indexOf("part") > 0 || params.arrParam == "false"))
                forms.m_arriveByForm.setValue('false');
            if(params.arr && (params.arr.indexOf("part") > 0 || params.arr == "false"))
                forms.m_arriveByForm.setValue('false');
            if(params.Arr && (params.Arr.indexOf("part") > 0 || params.Arr == "false"))
                forms.m_arriveByForm.setValue('false');
            if(params.after)
            {
                time = params.after.replace(/\./g, "");
                forms.m_time.setRawValue(time);
                forms.m_arriveByForm.setValue('false');
                time = true;
            }
            else if(params.by)
            {
                time = params.by.replace(/\./g, "");
                forms.m_time.setRawValue(time);
                forms.m_arriveByForm.setValue('true');
                time = true;
            }

            if(params.time)
            {
                time = params.time.replace(/\./g, "");
                forms.m_time.setRawValue(time);
                time = true;
            }

            if(params.mode)
            {
                forms.m_modeForm.setValue(params.mode);
                if(this.m_optionsManager)
                {
                    this.m_optionsManager.doMode(params.mode);
                }
            }

            // optimize / minimize option : how to optimize the trip, safest, quickets, least tranfers, etc...
            if(params.min)
                params.opt = params.min;
            if(params.opt)
            {
                forms.m_optimizeForm.setValue(params.opt);
                if(this.m_optionsManager)
                {
                    this.m_optionsManager.doOpt(params.opt);
                }
            }

            if(params.maxWalkDistance)
                forms.m_maxWalkDistanceForm.setValue(params.maxWalkDistance);
            if(params.wheelchair && this.showWheelchairForm)
                forms.m_wheelchairForm.setValue(params.wheelchair);

            this.setDirtyRawInput(params.routerId, forms.m_routerIdForm);

            // stupid trip planner form processing...

            // Hour=7&Minute=02&AmPm=pm
            if(!time && params.Hour && params.Minute && params.AmPm)
            {
                time = params.Hour + ":" +  params.Minute + " " + params.AmPm.toLowerCase();
                time = time.replace(/\./g, "");
                forms.m_time.setRawValue(time);
                time = true;
            }

            // &month=Oct&day=7
            if(!date && params.month && params.day)
            {
                var d = new Date();
                var y = d.getFullYear();
                var n = d.getMonth();
                var m = otp.util.DateUtils.getMonthAsInt(params.month);
                if(m < n) // assume a month that is prior to now means next year
                    y++;

                if(params.day.length == 1) 
                    params.day = "0" + params.day;

                date = m + "/" + params.day + "/" + y;
                forms.m_date.setRawValue(date);
                date = true;
            }
        }
    },

    /** */
    getMaxDistance : function()
    {
        var retVal = null;
        try
        {
            var self = otp.planner.StaticForms.THIS; 
            retVal = self.m_maxWalkDistanceForm.getValue();
        }
        catch(e)
        {}
        return retVal;
    },


    /** */
    setMaxDistance : function(val)
    {
        try
        {
            var self = otp.planner.StaticForms.THIS; 
            retVal = self.m_maxWalkDistanceForm.setValue(val);
        }
        catch(e)
        {}
    },

    /**
     * returns current state (values) of the trip planner forms
     * used for creating a URL populated with values to the trip planner
     */
    getFormData : function(url)
    {
        var retVal = {};
        retVal.url       = url;
        retVal.itinID    = "1";
        retVal.fromPlace = this.getFrom();
        retVal.toPlace   = this.getTo();
        retVal.date      = this.m_date.getRawValue();
        retVal.time      = this.m_time.getRawValue();
        retVal.arriveBy  = this.m_arriveByForm.getRawValue();
        retVal.opt       = this.m_optimizeForm.getValue();
        retVal.routerId  = this.m_routerIdForm.getValue();

        if(retVal.opt == "TRIANGLE") {
            var triopts = this.m_bikeTriangle.getFormData();
            otp.extend(retVal, triopts);
        }
        var d = this.m_maxWalkDistanceForm.getValue();
        retVal.maxWalkDistance = d * 1.0;
        retVal.mode            = this.m_modeForm.getValue();
        if(this.showWheelchairForm)
            retVal.wheelchair      = this.m_wheelchairForm.getValue();
        retVal.intermediate_places = ''; //TODO: intermediate stops

        // break up the from coordinate into lat & lon
        var coord = this.m_fromCoord;
        if(coord == otp.util.Constants.BLANK_LAT_LON)
            coord = retVal.fromPlace;
        retVal.fromLat = otp.util.ObjUtils.getLat(coord);
        retVal.fromLon = otp.util.ObjUtils.getLon(coord);

        // break up the to coordinate into lat & lon
        coord = this.m_toCoord;
        if(coord == otp.util.Constants.BLANK_LAT_LON)
            coord = retVal.toPlace;
        retVal.toLat   = otp.util.ObjUtils.getLat(coord);
        retVal.toLon   = otp.util.ObjUtils.getLon(coord);

        try
        {
            retVal.time = retVal.time.replace(/\./g, "");
        }
        catch(e)
        {
        }

        return retVal;
    },

    /**
     * 
     */
    clearFrom : function(formToo)
    {
        this.m_fromCoord = otp.util.Constants.BLANK_LAT_LON; 

        if(formToo && formToo === true)
        {
            this.m_fromForm.clear();
        }
    },

    /** 
     */
    clearTo : function(formToo)
    {
        this.m_toCoord = otp.util.Constants.BLANK_LAT_LON; 

        if(formToo && formToo === true)
        {
            this.m_toForm.clear();
        }
    },

    /** */
    selectFrom : function(combo, record, num)
    {
         this.m_fromCoord = otp.util.ExtUtils.getCoordinate(record);
    },

    /** */
    selectTo : function(combo, record, num) {
        this.m_toCoord = otp.util.ExtUtils.getCoordinate(record);
    },

    /** */
    getContextMenu : function(cm) {
        if(cm != null)
            this.contextMenu = cm;

        var retVal = [
        {
            text    : this.locale.contextMenu.fromHere,
            iconCls : 'cFromHere',
            scope   : this,
            handler : function () {
                var ll = this.contextMenu.getYOffsetMapCoordinate();
                var latlon = ll.lat + "," + ll.lon;
                this.setFrom(latlon, ll.lat, ll.lon);
                this.m_fromForm.setRawValue(latlon);
                this.m_fromForm.getComboBox().clearInvalid();
            }
        }
        ,
        {
            text    : this.locale.contextMenu.toHere,
            iconCls : 'cToHere',
            scope   : this,
            handler : function () {
                var ll = this.contextMenu.getYOffsetMapCoordinate();
                var latlon = ll.lat + "," + ll.lon;
                this.setTo(latlon, ll.lat, ll.lon);
                this.m_toForm.setRawValue(latlon);
                this.m_toForm.getComboBox().clearInvalid();
            }
        }
        ];
        return retVal;
    },


    /**
     * from & to form creation
     */
    makeMainPanel : function()
    {
        var fromToForms = this.makeFromToForms();
        var dateTime    = this.makeDateTime();
        var fromToArray = [fromToForms, dateTime];

        // fromToOverride will be shown atop the display of the from & to forms (thus turning the from & to forms off)
        if(this.fromToOverride && (this.fromToOverride != "" || this.fromToOverride != " "))
        {
            fromToArray = [this.fromToOverride, dateTime];
        }

        var fromToFP = new Ext.form.FieldSet({
            labelWidth:  40,
            border:      false,
            items:       fromToArray
        });
        this.m_fromToFP = fromToFP;

        var optForms = this.makeOptionForms();
        var optFP = new Ext.form.FieldSet({
            labelWidth:  110,
            border:      false,
            items:       optForms
        });
        
        this.m_routerIdForm = new Ext.form.Hidden({
            id:             'trip-routerid-form',
            name:           'routerId',
            value:          this.routerId
        });

        this.m_submitButton = new Ext.Button({
            text:    this.locale.tripPlanner.labels.planTrip,
            id:      'trip-submit',
            scope:   this,
            style:   'background-color:FF0000;',
            handler: this.submit
        });

        this.m_toPlace   = new Ext.form.Hidden({name: 'toPlace',   value: ''});
        this.m_fromPlace = new Ext.form.Hidden({name: 'fromPlace', value: ''});
        this.m_intermediatePlaces = new Ext.form.Hidden({name: 'intermediatePlaces', value: ''});

        var conf = {
            title:       this.locale.tripPlanner.labels.tabTitle,
            id:          'form-tab',
            buttonAlign: 'center',
            border:      false,
            keys:        {key: [10, 13], scope: this, handler: this.submit},
            items:       [  fromToFP,
                            optFP,
                            this.m_routerIdForm,
                            this.m_toPlace,
                            this.m_fromPlace,
                            this.m_intermediatePlaces,
                            this.m_submitButton
                         ],

            // configure how to read the XML Data
            reader:      this.m_xmlRespRecord,

            // reusable error reader class defined at the end of this file
            errorReader: new Ext.form.XmlErrorReader()
        };
        this.m_panel = new Ext.FormPanel(conf);
        
        this.m_panel.on({
                scope:           this,
                beforeaction:    this.preSubmit,
                actionfailed:    this.submitFailure,
                actioncomplete:  this.submitSuccess
        });
        
    },
    
    /**
     * Restore the points of interest when the panel is activated
     * Note that currently planner.js calls this function directly
     * instead of it being wired in as an event handler, so that this
     * can be called after the tabchange handler has been called
     * (otherwise the renderer would clear the work done here because
     * tabchange gets triggered after activate)
     */
    panelActivated: function() {
        if (!this.poi) {
            return;
        }
        if (this.m_fromCoord && this.m_fromCoord.length > 2) {
            var x = otp.util.ObjUtils.getLat(this.m_fromCoord);
            var y = otp.util.ObjUtils.getLon(this.m_fromCoord);
            if (x != 0 && y != 0) {
                this.poi.setFrom(y, x, this.m_fromCoord);
            }
        }
        if (this.m_toCoord && this.m_toCoord.length > 2) {
            var x = otp.util.ObjUtils.getLat(this.m_toCoord);
            var y = otp.util.ObjUtils.getLon(this.m_toCoord);
            if (x != 0 && y != 0) {
                this.poi.setTo(y, x, this.m_toCoord);
            }
        }
    },

    /**
     * from & to form creation
     * NOTE: defining fromToOverride (needs to be at least a Template, of not another form) will 
     *       override the display of the from & to defined here
     */
    makeFromToForms : function()
    {
        // step 1: these give the from & to forms 'memory' -- all submitted strings are saved off in the cookie for latter use
        Ext.state.Manager.setProvider(new Ext.state.CookieProvider());
        Ext.state.Manager.getProvider();

        // step 2: create the forms
        var comboBoxOptions = {layout: 'anchor', label: '', cls: 'nudgeRight', msgTarget: "under"};
        var fromFormOptions = Ext.apply({}, {id: 'from.id', name: 'from', emptyText: this.locale.tripPlanner.labels.from}, comboBoxOptions);
        var toFormOptions = Ext.apply({}, {id: 'to.id',   name: 'to',   emptyText: this.locale.tripPlanner.labels.to}, comboBoxOptions);
        if(this.geocoderEnabled()) {
            fromFormOptions.changeHandler = this.geocoder.fromChanged.createDelegate(this.geocoder);
            toFormOptions.changeHandler   = this.geocoder.toChanged.createDelegate(this.geocoder);
        }
        this.m_fromForm = new otp.core.ComboBox(fromFormOptions);
        this.m_toForm   = new otp.core.ComboBox(toFormOptions);
        var rev  = new Ext.Button({
            tooltip:   this.locale.buttons.reverseMiniTip,
            id:        "form.reverse.id",
            iconCls:   "reverse-button",
            cls:      'formReverseButton', 
            hideLabel: true,
            scope:     this,
            tabIndex:  -1,
            handler : function(obj)
            {
                // this reverses the form values
                this.m_fromForm.reverse(this.m_toForm);
                // but we should also reverse the coordinates themselves
                var tmp = this.m_fromCoord;
                this.m_fromCoord = this.m_toCoord;
                this.m_toCoord = tmp;
                // and also update the style on the map markers
                if (this.poi) {
                    this.poi.reverseStyles();
                }
                otp.util.Analytics.gaEvent(otp.util.Analytics.OTP_TRIP_FORM_REVERSE);
            }
        });

        // change events will clear out the from/to coordinate forms (don't want coords from old places getting confused with new requests)
        if (this.geocoderEnabled()) {
            // but we only do this when the geocoder isn't enabled, because the forms have a separate change listener
            // that manages this state when the geocoder is enabled
            this.m_fromForm.getComboBox().on({scope: this, focus  : this.clearFrom  });
            this.m_fromForm.getComboBox().on({scope: this, select : this.selectFrom });
            this.m_toForm.getComboBox().on(  {scope: this, focus  : this.clearTo    });
            this.m_toForm.getComboBox().on(  {scope: this, select : this.selectTo   });
        }

        var inputPanel = {
            xtype:    'panel',
            border:    false,
            layout:    'column',
            items: [
                {
                    columnWidth: 0.90,
                    layout: 'form',
                    border: false,
                    items: [
                        this.m_fromForm.getComboBox(),
                        this.m_toForm.getComboBox()
                    ]
                }
                ,
                {
                    bodyStyle: 'padding-top:15px',
                    columnWidth: 0.07,
                    layout: 'anchor',
                    border: false,
                    items: [rev]
                }
            ]
        };

        return inputPanel;
    },

    /**
     * from & to form creation
     */
    makeDateTime : function()
    {
        this.m_date = new Ext.form.DateField({
            id:         'trip-date-form',
            fieldLabel: this.locale.tripPlanner.labels.date,
            name:       'date',
            format:     'm/d/Y',
            allowBlank: false,
            msgTarget:  'qtip',
            anchor:     "95%",
            value:      new Date().format('m/d/Y')
        });

        this.m_arriveByStore = otp.util.ExtUtils.makeStaticPullDownStore(this.locale.tripPlanner.arriveDepart);
        this.m_arriveByForm  = new Ext.form.ComboBox({
                id:             'trip-arrive-form',
                name:           'arriveBy',
                hiddenName:     'arriveBy',
                fieldLabel:     this.locale.tripPlanner.labels.when,
                store:          this.m_arriveByStore,
                value:          this.m_arriveByStore.getAt(0).get('opt'),
                displayField:   'text',
                valueField:     'opt',
                anchor:         this.FIELD_ANCHOR,
                mode:           'local',
                triggerAction:  'all',
                editable:       false,
                allowBlank:     false,
                lazyRender:     false,
                typeAhead:      true,
                forceSelection: true,
                selectOnFocus:  true
        });

        this.m_time = new Ext.ux.form.Spinner({
                id         : 'trip-time-form',
                fieldLabel : this.locale.tripPlanner.labels.when,
                accelerate : true,
                width      : 85,
                msgTarget  : 'qtip',
                value      : new Date().format('g:i a'),
                strategy   : new Ext.ux.form.Spinner.TimeStrategy({format:'g:i a'}),
                name       : 'time'
        });

        var timePanel = {
            xtype:    'panel',
            border:    false,
            layout:    'column',
            autoWidth: true,
            items: [
                {
                    columnWidth: 0.37,
                    layout: 'form',
                    border: false,
                    items: [this.m_arriveByForm]
                }
                ,
                {
                    columnWidth: 0.33,
                    layout: 'anchor',
                    border: false,
                    items: [this.m_date]
                }
                ,
                {
                    columnWidth: 0.30,
                    layout: 'anchor',
                    border: false,
                    //labelWidth: 5,
                    items: [this.m_time]
                }
            ]
        };

        return timePanel;
    },


    /**
     * makes the forms for things like walk distance and mode (bus / train / both) 
     */
    makeOptionForms : function()
    {
        this.m_maxWalkDistanceStore  = otp.util.ExtUtils.makeStaticPullDownStore(this.locale.tripPlanner.maxWalkDistance);
        this.m_optimizeStore = otp.util.ExtUtils.makeStaticPullDownStore(this.locale.tripPlanner.options);
        this.m_modeStore     = otp.util.ExtUtils.makeStaticPullDownStore(this.locale.tripPlanner.mode);

        this.m_optimizeForm = new Ext.form.ComboBox({
            id:             'trip-optimize-form',
            name:           'optimize',
            hiddenName:     'optimize',
            fieldLabel:     this.locale.tripPlanner.labels.minimize,
            store:          this.m_optimizeStore,
            value:          this.m_optimizeStore.getAt(1).get('opt'),
            displayField:   'text',
            valueField:     'opt',
            anchor:         this.FIELD_ANCHOR,
            mode:           'local',
            triggerAction:  'all',
            editable:       false,
            allowBlank:     false,
            lazyRender:     false,
            typeAhead:      true,
            forceSelection: true,
            selectOnFocus:  true,
            // lastQuery prevents any filters from being cleared initially
            lastQuery:      ''
        });

        this.m_maxWalkDistanceForm = new Ext.form.ComboBox({
            id:             'trip-walking-form',
            name:           'maxWalkDistance',
            hiddenName:     'maxWalkDistance',
            fieldLabel:     this.locale.tripPlanner.labels.maxWalkDistance,
            store:          this.m_maxWalkDistanceStore,
            value:          this.m_maxWalkDistanceStore.getAt(2).get('opt'),
            displayField:   'text',
            valueField:     'opt',
            anchor:         this.FIELD_ANCHOR,
            mode:           'local',
            triggerAction:  'all',
            editable:       false,
            allowBlank:     false,
            lazyRender:     false,
            typeAhead:      true,
            forceSelection: true,
            selectOnFocus:  true
        });

        this.m_modeForm  = new Ext.form.ComboBox({
            id:             'trip-mode-form',
            name:           'mode',
            hiddenName:     'mode',
            fieldLabel:     this.locale.tripPlanner.labels.mode,
            store:          this.m_modeStore,
            value:          this.m_modeStore.getAt(0).get('opt'),
            displayField:   'text',
            valueField:     'opt',
            anchor:         this.FIELD_ANCHOR,
            mode:           'local',
            triggerAction:  'all',
            editable:       false,
            allowBlank:     false,
            lazyRender:     false,
            typeAhead:      true,
            forceSelection: true,
            selectOnFocus:  true
        });

        this.m_wheelchairForm = new Ext.form.Checkbox({
            id:             'trip-wheelchair-form',
            name:           'wheelchair',
            hiddenName:     'wheelchair',
            fieldLabel:     this.locale.tripPlanner.labels.wheelchair,
            inputValue:     'true',
            displayField:   'text',
            valueField:     'opt',
            anchor:         this.FIELD_ANCHOR,
            mode:           'local',
            triggerAction:  'all',
            editable:       false,
            allowBlank:     false,
            lazyRender:     false,
            typeAhead:      true,
            forceSelection: true,
            selectOnFocus:  true
        });
        
        this.m_bikeTriangleContainer = new Ext.Panel({  
            name:           'bikeTriangleContainer'
        });

        this.m_bikeTriangle = new otp.planner.BikeTriangle({
            container:      this.m_bikeTriangleContainer
        }); 

        // default transit true filter
        var filter = otp.planner.FormsOptionsManagerStatic.getOptimizeFilter(true, false);
        this.m_optimizeForm.getStore().filterBy(filter);

        if (this.useOptionDependencies) {
            var usecfg = {
                mode:         this.m_modeForm,
                optimize:     this.m_optimizeForm,
                maxWalk:      this.m_maxWalkDistanceForm,
                locale:       this.locale,
                bikeTriangle: this.m_bikeTriangle
            };

            if(this.showWheelchairForm)
                usecfg.wheelchair = this.m_wheelchairForm;
            this.m_optionsManager = new otp.planner.FormsOptionsManager(usecfg);
        }

        var retVal = [this.m_modeForm, this.m_optimizeForm, this.m_bikeTriangleContainer, this.m_maxWalkDistanceForm];
        if(this.showWheelchairForm)
            retVal.push(this.m_wheelchairForm);

        return retVal;

    },

    /** TODO refactor and clean this up -- think intermediatePoints geocoding*/
    setFormErrorMessage : function(comboBoxIdentifier, message)
    {
        var errMsg = this.form.locale.tripPlanner.geocoder.error;
        if(message)
            errMsg = message;

        if (comboBoxIdentifier === "from") {
            this.m_fromForm.getComboBox().markInvalid(errMsg);
        } else if (comboBoxIdentifier === "to") {
            this.m_toForm.getComboBox().markInvalid(errMsg);
        }
    },

    /** utility to */
    geocoderEnabled : function()
    {
        return this.geocoder && this.geocoder.enabled;
    },

    /** utility to see if we're geocoding at the moment...*/
    isBusyGeocoding : function()
    {
        var retVal = false;
        try
        {
            if(this.geocode.m_fromGeocoding || this.geocode.m_toGeocoding)
                retVal = true;
        }
        catch(e) {}
        return retVal;
    },

    CLASS_NAME: "otp.planner.Forms"
};

otp.planner.Forms = new otp.Class(otp.planner.StaticForms);
