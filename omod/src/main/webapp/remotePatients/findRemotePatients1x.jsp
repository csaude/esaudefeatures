<%@ page import="org.openmrs.util.OpenmrsConstants" %>
<%@ include file="/WEB-INF/template/include.jsp" %>

<openmrs:require privilege="View Patients" otherwise="/login.htm" redirect="/module/esaudefeatures/findRemotePatients.htm" />

<openmrs:message var="pageTitle" code="esaudefeatures.remote.patients" scope="page"/>
<%@ include file="/WEB-INF/template/header.jsp" %>

<h2><openmrs:message code="esaudefeatures.remote.patients.search"/></h2>

<br />
<c:if test="${not empty remoteServerUrl}">
    <h3><openmrs:message code="esaudefeatures.remote.server.url" scope="page"/>: ${remoteServerUrl}</h3>
</c:if>
<c:if test="${authenticatedUser == null}">
    <h2>Auth is null, but why?</h2>
</c:if>
<c:if test="${authenticatedUser != null}">
    <openmrs:require privilege="View Patients" otherwise="/login.htm" redirect="/index.htm" />
    <style>
        #found-patients_wrapper{
            /* Removes the empty space after datatable if the table is short */
            /* Over ride the value set by datatables */
            min-height: 150px; height: auto !important;
        }
    </style>
    <openmrs:htmlInclude file="/scripts/jquery/dataTables/css/dataTables_jui.css"/>
    <openmrs:htmlInclude file="/scripts/jquery/dataTables/js/jquery.dataTables.min.js"/>

    <openmrs:globalProperty key="patient.listingAttributeTypes" var="attributesToList"/>

    <script type="text/javascript">
        var localOpenmrsContextPath = '${pageContext.request.contextPath}';
        var remoteServerUrl = "${remoteServerUrl}";
        var base64encodedCredos = "${remoteServerAuth}";
        var patientTable = null;
        var lastSearchedText = null;
        var foundPatientList = null;
        const MIN_SEARCH_LENGTH = 3;
        const EMPTY_COLUMN_HEADER_ID = 'empty-header-column';
        const DATE_DISPLAY_OPTIONS = { year: 'numeric', month: 'short', day: 'numeric' };
        const JSESSIONID_COOKIE = 'JSESSIONID';

        var searchController = null;
        function searchPatientFromRemoteServer(searchText) {
            var requestHeaders = new Headers({
                'Content-Type': 'application/json',
                'Authorization': 'Basic ' + base64encodedCredos
            });

            var requestOptions = {
                method: 'GET',
                headers: requestHeaders,
                redirect: 'follow'
            };

            var searchUrl = patientSearchUrl() + "&q=" + searchText;
            if(searchController !== null) {
                searchController.abort();
            }
            searchController = new AbortController();
            requestOptions.signal = searchController.signal;
            fetch(searchUrl, requestOptions)
                .then(response => response.json())
                .then(data => {
                    // Generate table.
                    console.log("Here and data ni: ", data);
                    var results = [];
                    if(Array.isArray(data.results) && data.results.length > 0) {
                        foundPatientList = data.results;
                        results = mapResults(data.results);
                    }
                    refreshTable(patientTable, results);
                }).catch(error => console.log('error', error));
        }

        function patientSearchUrl() {
            var patientSearchUrl = remoteServerUrl;
            if(remoteServerUrl !== null && !remoteServerUrl.endsWith("/")) {
                patientSearchUrl += "/"
            }
            if(patientSearchUrl == null || patientSearchUrl == undefined) {
                console.log("Remote Server URL is not set");
                return;
            }

            return patientSearchUrl += "ws/rest/v1/patient?v=full"
        }

        function mapResults(results) {
            return results.map(result => {
                var mapped = [ result.uuid, result.identifiers[0].identifier, result.person.display, result.person.gender ];
                var birthDate = new Date(result.person.birthdate);
                mapped.push(birthDate.toLocaleDateString(undefined, DATE_DISPLAY_OPTIONS));
                mapped.push(result.person.age);
                if(result.person.preferredAddress) {
                    mapped.push(result.person.preferredAddress.cityVillage);
                } else if(Array.isArray(result.person.addresses && result.person.addresses.length > 0)) {
                    mapped.push(result.person.addresses[0].cityVillage);
                } else {
                    mapped.push('<openmrs:message code="esaudefeatures.remote.patients.no.address.found"/>');
                }
                return mapped;
            });
        }

        function insertDetailsColumnInResultsTable(oTable) {
            /*
             * Insert a 'details' column to the table
             */
            var nCloneTh = document.createElement('th');
            nCloneTh.setAttribute("id", EMPTY_COLUMN_HEADER_ID);

            var nCloneTd = document.createElement('td');
            nCloneTd.innerHTML = '<img src="${pageContext.request.contextPath}/moduleResources/esaudefeatures/images/details_open.png" />';
            nCloneTd.className = "center";

            $j('#found-patients thead tr').each(function () {
                this.insertBefore(nCloneTh, this.childNodes[1]);
            });

            $j('#found-patients tbody tr').each( function () {
                this.insertBefore(nCloneTd.cloneNode(true), this.childNodes[1] );
            });

            /* Add event listener for opening and closing details
             * Note that the indicator for showing which row is open is not controlled by DataTables,
             * rather it is done here
             */
            $j('#found-patients tbody td img').live('click', function () {
                var nTr = this.parentNode.parentNode;
                if ( this.src.match('details_close') ) {
                    /* This row is already open - close it */
                    this.src = "${pageContext.request.contextPath}/moduleResources/esaudefeatures/images/details_open.png";
                    oTable.fnClose(nTr);
                }
                else {
                    /* Open this row */
                    this.src = "${pageContext.request.contextPath}/moduleResources/esaudefeatures/images/details_close.png";

                    // Fetch Similar records first.
                    // 1. First try by uuid.
                    var rowData = oTable.fnGetData(nTr);
                    var patientUuid = rowData[0];
                    var patient = foundPatientList.find(patient => patient.uuid === patientUuid);
                    var localPatietSearchUrl = localOpenmrsContextPath + '/ws/rest/v1/patient/' + patientUuid + '?v=full';
                    var requestHeaders = new Headers({
                        'Content-Type': 'application/json',
                    });

                    var requestOptions = {
                        method: 'GET',
                        headers: requestHeaders,
                        redirect: 'follow'
                    };

                    fetch(localPatietSearchUrl, requestOptions)
                        .then(response => {
                            if(response.status === 200) {
                                response.json().then(localPatient => {
                                    var remotepPatientDetailsTitle ='<openmrs:message code="esaudefeatures.remote.patients.remote.patient.details"/>';
                                    var details = createPatientDetailsHtmlTable(patient, remotepPatientDetailsTitle, true, true);
                                    var detailsTitle = '<openmrs:message code="esaudefeatures.remote.patients.same.uuid.local"/>';
                                    var localPatientTable = '<div style="float:left; border:2.5px solid red; background-color: #FF9033">'
                                        + createPatientDetailsHtmlTable(localPatient, detailsTitle, false)
                                        + '</div>'
                                    details += localPatientTable;
                                    oTable.fnOpen(nTr, details, 'details' );
                                })

                            } else if(response.status === 404) {
                                // TODO: Go for identifiers & names (After discussion with the team)
                            } else {
                                var details = createPatientDetailsHtmlTable(patient, remotepPatientDetailsTitle);
                                oTable.fnOpen(nTr, details, 'details' );
                            }
                        })
                        .catch(error => console.log('error', error));
                }
            });
        }

        function createPatientPayload(restPatientPayload) {
            return {
                person: restPatientPayload.uuid,
                identifiers: restPatientPayload.identifiers.filter(identifier => !identifier.voided).map(identifier => {
                    var identifierPayload = {
                        identifier: identifier.identifier,
                        identifierType: identifier.identifierType.uuid,
                        preferred: identifier.preferred
                    };

                    if(identifier.location && typeof identifier.location === 'object') {
                        identifierPayload.location = identifier.location.uuid
                    }
                })
            };
        }

        function createPersonPayload(restPersonPayload) {
            var personPayload = {
                uuid: restPersonPayload.uuid,
                gender: restPersonPayload.gender,
                birthdate: restPersonPayload.birthdate,
                birthdateEstimated: restPersonPayload.birthdateEstimated
            };

            if(restPersonPayload.dead) {
                Object.assign(personPayload, {
                    dead: true,
                    deathDate: restPersonPayload.deathDate,
                    causeOfDeath: restPersonPayload.causeOfDeath
                })
            }

            if(Array.isArray(restPersonPayload.names) && restPersonPayload.names.length > 0) {

                personPayload.names = restPersonPayload.names.filter(name => !name.voided).map(name => {
                    return {
                        uuid: name.uuid,
                        givenName: name.givenName,
                        middleName: name.middleName,
                        familyName: name.familyName,
                    };
                });
            }

            if(Array.isArray(restPersonPayload.addresses) && restPersonPayload.addresses.length > 0) {
                var allKeys = Object.keys(restPersonPayload.addresses[0]);
                var ignoredKeys = [ "uuid", "preferred", "voided", "display", "links", "resourceVersion" ];

                ignoredKeys.forEach(ignored => {
                    var index = allKeys.indexOf(ignored);
                    allKeys.splice(index, 1);
                });

                var addressesToSend = restPersonPayload.addresses.filter(address => !address.voided);
                if(addressesToSend.length > 0) {
                    personPayload.addresses = [];
                    addressesToSend.addresses.filter(address => !address.voided).forEach(address => {

                        var addressPayload = {
                            uuid: address.uuid,
                            preferred: address.preferred,
                            voided: address.voided
                        };


                        allKeys.forEach(key => {
                            if (address[key] !== null) {
                                addressPayload[key] = address[key];
                            }
                        });

                        personPayload.addresses.push(addressPayload);
                    });
                }
            }

            if(Array.isArray(restPersonPayload.attributes) && restPersonPayload.attributes.length > 0) {
                var attributesToSend = restPersonPayload.attributes.filter(attribute => !attribute.voided);
                if(attributesToSend.length > 0) {
                    personPayload.attributes = attributesToSend.map(attribute => {
                        return {
                            "attributeType": attribute.attributeType.uuid,
                            "value": attribute.value
                        }
                    });
                }
            }

            return personPayload;
        }

        function refreshTable(oTable, data) {
            oTable.fnClearTable();
            oTable.fnAddData(data);

            oTable.fnDraw();

            if(Array.isArray(data) && data.length > 0) {
                insertDetailsColumnInResultsTable(oTable);
                oTable.fnSetColumnVis(0, false);
            } else {
                // Remove the added column in the header row if it already added.
                var emptyColumnHeader = document.getElementById(EMPTY_COLUMN_HEADER_ID);
                if(emptyColumnHeader !== null) {
                    emptyColumnHeader.parentNode.removeChild(emptyColumnHeader);
                }
            }
        }

        function createPatientDetailsHtmlTable(patient, title, addImportButton, disableImportButton) {

            var patientDetailsTable = '<table cellpadding="5" cellspacing="0" border="0" style="display: inline; padding-left:10px; border-spacing: 5px;">';
            var patientName = "mama"; //patient.person.names[0];
            if(patient.person.preferredName) {
                patientName = patient.person.preferredName;
            }

            if(title) {
                patientDetailsTable += '<tr><td colspan="2">' + title + '</td></tr>';
            }
            patientDetailsTable += '<tr><td colspan="2" style="border-bottom: solid; border-top: solid;"><openmrs:message code="esaudefeatures.remote.patients.names"/></td></tr>';
            patientDetailsTable += '<tr><td><openmrs:message code="esaudefeatures.remote.patients.givenName"/><td>' + patientName.givenName + '</td></tr>';
            patientDetailsTable += '<tr><td><openmrs:message code="esaudefeatures.remote.patients.middleName"/><td>' + patientName.middleName + '</td></tr>';
            patientDetailsTable += '<tr><td><openmrs:message code="esaudefeatures.remote.patients.familyName"/><td>' + patientName.familyName + '</td></tr>';

            patientDetailsTable += '<tr><td colspan="2" style="border-bottom: solid; border-top: solid; margin-top:15px;"><openmrs:message code="esaudefeatures.remote.patients.identifiers"/></td></tr>';
            if(Array.isArray(patient.identifiers) && patient.identifiers.length > 0) {
                for(let identifier of patient.identifiers) {
                    patientDetailsTable += '<tr><td>' + identifier.identifierType.display + ':</td><td>' + identifier.identifier + '</td>';
                }
            } else {
                patientDetailsTable += '<tr><td colspan="2"><openmrs:message code="esaudefeatures.remote.patients.no.identifiers"/> </td></tr>';
            }

            patientDetailsTable += '<tr><td colspan="2" style="border-bottom: solid; border-top:solid;"><openmrs:message code="esaudefeatures.remote.patients.attributes"/></td></tr>';
            if(Array.isArray(patient.person.attributes) && patient.person.attributes.length > 0) {
                for(let personAttribute of patient.person.attributes) {
                    patientDetailsTable += '<tr><td>' + personAttribute.attributeType.display + ':</td><td>' + personAttribute.value + '</td>';
                }
            } else {
                patientDetailsTable += '<tr><td colspan="2"><openmrs:message code="esaudefeatures.remote.patients.no.attributes"/></td></tr>';
            }
            patientDetailsTable += '<tr><td>uuid:<td>' + patient.uuid + '</td></tr>';

            if(addImportButton) {
                if(disableImportButton) {
                    patientDetailsTable += '<tr><td colspan="2"><input type="button" disabled value="' + '<openmrs:message code="esaudefeatures.remote.patients.remote.import.patient"/>' +
                        '" onclick="importPatient(\'' + patient + '\')"/></td></tr>';
                } else {
                    patientDetailsTable += '<tr><td colspan="2"><input type="button" value="' + '<openmrs:message code="esaudefeatures.remote.patients.remote.import.patient"/>' +
                    '" onclick="importPatient(\'' + patient + '\')"/></td></tr>';
                }
            }

            patientDetailsTable += '</table>';

            return patientDetailsTable;
        }

        function importPatient(patient) {
            console.log("Importing patient with uuid: " + patient.uuid);

            var localPersonRestUrl = localOpenmrsContextPath + '/ws/rest/v1/person';
            var localPatientRestUrl = localOpenmrsContextPath + '/ws/rest/v1/patient';

            var requestHeaders = new Headers();
            requestHeaders.append("Content-Type", "application/json");

            var rawPerson = JSON.stringify(createPersonPayload(patient.person));
            var rawPatient = JSON.stringfy(createPatientPayload(patient));

            var requestOptions = {
                method: 'POST',
                headers: requestHeaders,
                body: rawPerson,
            };

            fetch(localPersonRestUrl, requestOptions)
                .then(response => response.json())
                .then(result => {
                    // Now push the patient.
                    requestOptions.body = rawPatient;
                    fetch(localPatientRestUrl, requestOptions)
                        .then(response => response.json())
                        .then(result => {
                            // At this point shit is good.
                        })
                        .catch(trouble => console.log('Error while importing patient record: ', trouble));
                })
                .catch(error => console.log('error', error));
        }

        $j(document).ready(function() {
            patientTable = $j('#found-patients').dataTable({
                aaData: [],
                bFilter: false,
                aaSorting: [[2, 'asc']]
            });

            $j("#find-remote-patients").on('change keyup cut paste', function(e) {
                var searchText = $j(this).val();
                if(searchText !== null) searchText.trim();
                if(lastSearchedText === null || searchText !== lastSearchedText) {
                    if(searchText.length >= MIN_SEARCH_LENGTH) {
                        searchPatientFromRemoteServer(searchText);
                    } else if(lastSearchedText !== null) {
                        // Subsequent searches with text less than minimum length.
                        foundPatientList = null;
                        refreshTable(patientTable, []);
                    }
                    lastSearchedText = searchText;
                }
            });
        });

    </script>

    <div>
        <b class="boxHeader"><openmrs:message code="esaudefeatures.remote.patients.search"/></b>
        <div class="box">
            <openmrs:message code="esaudefeatures.remote.patients.search" javaScriptEscape="true"/>
            <input type="text" id="find-remote-patients"
                   placeholder="<openmrs:message code="esaudefeatures.remote.patients.search.placeholder" javaScriptEscape="true"/>"/>
            <table id="found-patients" class="display nowrap" style="width:100%">
                <thead>
                <tr>
                    <th></th>
                    <th>Identifier</th>
                    <th>Full name</th>
                    <th>Gender</th>
                    <th>Birth date</th>
                    <th>Age</th>
                    <th>Address</th>
                </tr>
                </thead>
                <tbody></tbody>
            </table>
        </div>
    </div>
</c:if>

<%@ include file="/WEB-INF/template/footer.jsp" %>
